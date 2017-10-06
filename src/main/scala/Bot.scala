import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import regex._
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Success, Failure}
import sx.blah.discord.Discord4J
import sx.blah.discord.api.ClientBuilder
import sx.blah.discord.api.events.{Event, IListener}
import sx.blah.discord.handle.impl.events.ReadyEvent
import sx.blah.discord.handle.impl.events.guild.channel.message.MessageReceivedEvent
import sx.blah.discord.handle.obj.{IMessage, IUser, IChannel}
import sx.blah.discord.util.RequestBuffer

import D4jExtensions._

object Bot extends App with UserMonitor.ActionHandler {
  Discord4J.disableAudio()
  
  val botConfig = ConfigFactory.load("bot.config")
  
  val actorSystem = ActorSystem()
  
  val client = new ClientBuilder().withToken(botConfig.getString("bot.token")).
    setMaxMessageCacheCount(100).
    withMinimumDispatchThreads(1).
    withMaximumDispatchThreads(5).
    withIdleDispatchThreadTimeout(1, MINUTES).
    registerListener(DiscordListener).
    login()

  implicit class IteratorExt[T](val i: Iterator[T]) extends AnyVal {
    def nextOpt(): Option[T] = if (i.hasNext) Some(i.next) else None
  }
  
  val userMonitors = TrieMap[IUser, TrieMap[IChannel, ActorRef]]()
  lazy val theGuild = client.getGuilds.get(0)
  lazy val auditChannel = theGuild.getChannelByID(botConfig.getLong("bot.auditChannel"))
  lazy val moderatorRole = theGuild.getRoleByID(botConfig.getLong("bot.moderatorRol"))
  val requiredReports = botConfig.getInt("bot.requiredReports")
  
  object DiscordListener extends IListener[Event] {
    
    def handle(event: Event) = event match {
      case evt: ReadyEvent =>
        if (client.getGuilds.size != 1) {
          println("This bot can only be registered to one guild at a time")
          sys.exit(1)
        }
        if (auditChannel == null) {
          println(s"Configured audit channel ${botConfig.getLong("bot.auditChannel")} not found")
          sys.exit(1)
        }
        if (moderatorRole == null) {
          println(s"Configured moderator rol ${botConfig.getLong("bot.moderatorRol")} not found")
          sys.exit(1)
        }
        
        
      case evt: MessageReceivedEvent =>
        val content = evt.getMessage.getContent
        commands.find(_.action(evt.getMessage).isDefinedAt(evt.getMessage.getContent)) match {
          case Some(command) =>
            command.action(evt.getMessage)(evt.getMessage.getContent)
          case _ => evt.getMessage.reply(s"Sorry, I don't know the command: ${evt.getMessage.getContent}")
        }
        
    }
  }
  
  val commands = collection.mutable.ListBuffer[Command]()
  case class Command(name: String, description: String, requiresModerator: Boolean = false)(val action: IMessage => PartialFunction[String, Any]) { commands += this }
  
  Command("report <userId>", "Reports a user. USE RESPONSIBLY.")(msg => {
      case gr"""report $idStr(\d+)""" if msg.getChannel.isPrivate =>
        val msgId = idStr.toLong

        Option(theGuild.getMessageByID(msgId)) orElse 
        theGuild.getChannels.asScala.iterator.flatMap(c => Option(c.getMessageByID(msgId))).nextOpt() match {
          case None => msg.reply("Message not found.")
          case Some(reportedMsg) =>
            val reportedUser = reportedMsg.getAuthor
            val monitor = userMonitors.getOrElseUpdate(reportedUser, TrieMap()).getOrElse(
              reportedMsg.getChannel, actorSystem.actorOf(UserMonitor.props(reportedUser, reportedMsg.getChannel, requiredReports, Bot.this)))
            monitor ! UserMonitor.Reported(reportedMsg)
            msg.reply(s"User ${reportedUser.getName} reported")
        }
    })
  
  Command("appeal[ channelId]", "If you believe you have been wrongly timed out.")(msg => {
      case gr"""appeal(?: $idStr(\d+))?""" if msg.getChannel.isPrivate =>
        val chosenChannel = idStr.map {
          case str @ gr"\\d+" => Option(theGuild.getChannelByID(str.toLong))
          case other => theGuild.getChannelsByName(other).asScala.headOption
        }

        chosenChannel match {
          case Some(Some(chosen)) => 
            userMonitors.get(msg.getAuthor).flatMap(_.get(chosen)).fold[Unit](
              msg.reply(s"You are not timed out in channel ${chosen.getName}"))(_ ! UserMonitor.Appealed(msg))
          case Some(None) => msg.reply("Channel not found")
          case _ =>
            userMonitors.get(msg.getAuthor).fold[Unit](msg.reply("You are not timed out")) { timeouts =>
              timeouts.size match {
                case 1 => timeouts.head._2 ! UserMonitor.Appealed(msg)
                case 0 => msg.reply("You are not timed out")
                case other => msg.reply("You're timed out in multiple channels, you need to tell me for which channel you wish to appeal by doing `appeal channelId` where `channelId` can be either the snowflake id of the channel, or the channel name (assuming it is unique)")
              }
            }
        }
        
    })
  
  Command("unmute <userId> <channelId>", "Unmutes a timed out user", requiresModerator = true)(msg => {
      case gr"""unmute $userStrId(\d+) $channelStrId(\d+)""" if msg.getAuthor.hasRole(moderatorRole) =>
    })
  
  Command("list muted", "Shows all the people that are muted per channel", requiresModerator = true)(msg => {
      case "list muted" if msg.getAuthor.hasRole(moderatorRole) =>
        implicit val requestTimeout = Timeout(1.second)
        
        val flattenedMonitors = for {
          (user, monitors) <- userMonitors
          (channel, monitor) <- monitors
        } yield (monitor ? UserMonitor.IsMuted) map ((user, channel, _))
        
        Future.sequence(flattenedMonitors).onComplete {
          case Success(flattenedMonitors) =>
            val timedOutUsers = flattenedMonitors.collect { case (user, channel, data: UserMonitor.TimedOutData) => (user, channel, data) }.toVector
            val totalTimedOuts = timedOutUsers.size
            
            val report = new collection.mutable.ArrayBuffer[String](10)
            report += s"Total timed out users **$totalTimedOuts**."
            
            for {
              (channel, users) <- timedOutUsers.groupBy(_._2.getName)
              _ = report += s"Channel **$channel**:"
              (user, _, data) <- users
            } report += s"  ${user.getName} (${user.getNicknameForGuild(theGuild)}) until ${data.when.plusMillis(data.duration.toMillis)}"
            
            val (messages, remaining) = report.foldLeft(new collection.mutable.ArrayBuffer[String](report.length) -> new StringBuilder) {
              case ((messages, currentMessage), elem) => 
                if (currentMessage.size + elem.length < 2000) {
                  currentMessage append elem
                } else {
                  messages += currentMessage.result()
                  currentMessage.clear()
                }
                messages -> currentMessage
            }
            if (remaining.nonEmpty) messages += remaining.result()
            messages foreach (part => RequestBuffer.request(() => msg.reply(part)))
            
          case Failure(ex) => msg.reply("Something went wrong:\n```" + ex.getStackTrace.mkString("\n") + "```")
        }
    })
  
  Command("help", "Prints this help message")(msg => {
      case "help" =>
        val toShow = if (msg.getAuthor.hasRole(moderatorRole)) commands else commands.filterNot(_.requiresModerator)
        
        val maxCmdWidth = toShow.map(_.name.length).max
        val helpString = new StringBuilder
        toShow foreach (c => helpString.append(c.name.padTo(maxCmdWidth, ' ')).append(" - ").append(c.description).append("\n"))
        msg.reply("```\n" + helpString.toString + "```")
    })
  
  override def muteUser(user: IUser, duration: FiniteDuration, reports: Seq[IMessage]) = {
    
  }
  override def unmuteUser(user: IUser) = {
    
  }
  override def warnUser(user: IUser, message: IMessage) = {
    
  }
  override def notifyUserNotTimedOut(user: IUser, message: IMessage, channel: IChannel) = {
    
  }
  
//  def appeal(request: IMessage, timeout: TimedOut): Unit = {
//    request.reply("Appeal process initiated. You'll have to wait for the team of moderators to review your case.")
//    auditChannel.sendMessage(s"${moderatorRol.mention}, user ${request.getAuthor.mention} requested an appeal to the timeout he received in channel ${timeout.channel.mention}." + 
//                             s" The following users triggered the report:\n" + timeout.reports.map(m => s"${m.getAuthor.mention}: ${m.getTimestamp}").mkString("\n"))
//  }
}
