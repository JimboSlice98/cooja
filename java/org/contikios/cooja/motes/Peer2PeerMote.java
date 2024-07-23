package org.contikios.cooja.motes;

import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.contikios.cooja.AbstractionLevelDescription;
import org.contikios.cooja.COOJARadioPacket;
import org.contikios.cooja.ClassDescription;
import org.contikios.cooja.Cooja;
import org.contikios.cooja.Mote;
import org.contikios.cooja.MoteTimeEvent;
import org.contikios.cooja.MoteType;
import org.contikios.cooja.RadioPacket;
import org.contikios.cooja.Simulation;
import org.contikios.cooja.interfaces.ApplicationRadio;
import org.contikios.cooja.motes.DisturberMoteType.DisturberMote;

/**
 * Peer-to-peer mote
 *
 * @author James Helsby
 */

public class Peer2PeerMote extends AbstractApplicationMote {
  private ApplicationRadio radio;
  private Random rd;

  private static final long TRANSMISSION_DURATION = Simulation.MILLISECOND*5;  // UDP broadcast time: 5ms
  private static final long SEND_INTERVAL = Simulation.MILLISECOND*1000*60;  // Send request every 60 seconds
  private static final long MS = Simulation.MILLISECOND;
  private static final int MAX_CHANNELS = 3;
  private static final int TTL = 1000;
  private static final int LOG_LENGTH = 40;
  private static final int LOGTIME_LENGTH = 20;
  private static final int ACTION_LENGTH = 30;
  private long txCount = 0;
  private Map<String, Long> msgCache = new HashMap<>();

  public enum Action {
    BROADCAST_MESSAGE,
    BROADCAST_ATTESTATION,
    RELAY_MESSAGE,
    RELAY_ATTESTATION
  }

  public Peer2PeerMote(MoteType moteType, Simulation simulation) throws MoteType.MoteTypeCreationException {
    super(moteType, simulation);
  }
  

  protected void execute(long time) {
    // System.out.println("Mote " + getID() + " execute() function called");
    if (radio == null) {
      radio = (ApplicationRadio) getInterfaces().getRadio();
      rd = getSimulation().getRandomGenerator();
    }

    schedulePeriodicPacket(1000*MS*getID());
  }

  
  @Override
  public void receivedPacket(RadioPacket p) {
    String data = new String(p.getPacketData(), java.nio.charset.StandardCharsets.UTF_8);    
    String[] parts = data.split("\\|");
    if (parts.length != 5) {
      return;
    }

    try {
      long messageNum = Long.parseLong(parts[0]);
      int originNode = Integer.parseInt(parts[1]);
      int attestNode = Integer.parseInt(parts[2]);
      long messageData = Long.parseLong(parts[3]);
      int fromNode = Integer.parseInt(parts[4]);

      String messageID = messageNum + "|" + originNode + "|" + attestNode;
      String logMsg = "Rx: '" + messageID + "|" + messageData + "' from node: '" + fromNode + "'";
      logf(logMsg, null, radio.getChannel());
      
      // Handle duplicate packets
      if (msgCache.containsKey(messageID)) {
        // logf(logMsg, "duplicate", null);
        return;
      } else {
        msgCache.put(messageID, messageData);
      }
      
      // Handle incomming attestations
      if (attestNode != 0 && originNode != getID()) {        
        scheduleBroadcastPacket(messageID, messageData, TTL, 1, Action.RELAY_ATTESTATION);
        return;
      }
      else if (attestNode != 0) {
        logf(logMsg, "attestation received", radio.getChannel());
        return;
      }
      
      // Handle incomming messages and generate attestation
      scheduleBroadcastPacket(messageID, messageData, TTL, 1, Action.RELAY_MESSAGE);
      scheduleBroadcastPacket(messageNum + "|" + originNode + "|" + getID(), 0, TTL, 1, Action.BROADCAST_ATTESTATION);

    } catch (NumberFormatException e) {
      System.out.println("Mote " + getID() + " received bad data: " + e);
    }
  }


  private void schedulePeriodicPacket(long timeOffset) {
    getSimulation().scheduleEvent(new MoteTimeEvent(this) {
      @Override
      public void execute(long t) {        
        scheduleBroadcastPacket(txCount + "|" + getID() + "|" + 0, 0, TTL, 0, Action.BROADCAST_MESSAGE);
        schedulePeriodicPacket(0);
      }
    }, getSimulation().getSimulationTime() + SEND_INTERVAL + timeOffset);
  }


  private void scheduleBroadcastPacket(String messageID, long messageData, int ttl, int timeOffset, Action action) {
    if (ttl <= 0) {
      System.out.println(formatTime(getSimulation().getSimulationTimeMillis()) + "  ID " + getID() + ": TTL expired for " + messageID);
      return;
    }
  
    getSimulation().scheduleEvent(new MoteTimeEvent(this) {
      @Override
      public void execute(long t) {
        if (!attemptBroadcast(messageID, messageData, ttl, action)) {
          logf("Fx: '" + messageID + "|" + messageData, "rescheduled", null);
          scheduleBroadcastPacket(messageID, messageData, ttl, 1, action);
        }
      }
    }, getSimulation().getSimulationTime() + timeOffset * MS);
  }

  
  private boolean attemptBroadcast(String messageID, long messageData, int ttl, Action action) {
    logf(radio.getChannel() + " " + radio.isTransmitting() + " " + radio.isReceiving() + " " + radio.isInterfered(), null, null);
    if (!radio.isTransmitting() && !radio.isReceiving() && !radio.isInterfered()) {
      
      switch (action) {
        case BROADCAST_MESSAGE:
          messageData = getSimulation().getSimulationTime();
          logf("Tx: " + "'" + messageID + "'", null, radio.getChannel());
          msgCache.put(messageID, messageData);
          txCount++;
          break;
        
        case BROADCAST_ATTESTATION:
          messageData = getSimulation().getSimulationTime();
          logf("Ax: '" + messageID + "|" + messageData, null, radio.getChannel());
          msgCache.put(messageID, messageData);
          break;
    
        case RELAY_MESSAGE:
        case RELAY_ATTESTATION:
          logf("Bx: '" + messageID + "|" + messageData, action.toString().replace("_", " ").toLowerCase(), radio.getChannel());
          break;
    
        default:
          System.out.println("handleBroadcastAction() error");
      }
    
      radio.startTransmittingPacket(new COOJARadioPacket((messageID + "|" + messageData + "|" + getID()).getBytes(StandardCharsets.UTF_8)), TRANSMISSION_DURATION);
        scheduleChannelReset();
        return true;

    }
    return false;
  }

  
  private void scheduleChannelReset() {
    getSimulation().scheduleEvent(new MoteTimeEvent(Peer2PeerMote.this) {
      @Override
      public void execute(long t) {
        logf("Changing channel: " + radio.getChannel() + " -> " + -1, null, null);
        radio.setChannel(-1);
      }
    }, getSimulation().getSimulationTime() + TRANSMISSION_DURATION);
  }
  
  
  private void logf(String logMsg, String actionMsg, Integer channel) {
    String log;
    if (actionMsg != null) {
        log = String.format("%-" + LOG_LENGTH + "s", logMsg) + " -> " + actionMsg;
    } else {
        log = String.format("%-" + (LOG_LENGTH + ACTION_LENGTH) + "s", logMsg);
    }
    if (channel != null) {
        log = String.format("%-" + (LOG_LENGTH + ACTION_LENGTH) + "s", log) + " Channel: " + channel;
    }
    log(log);
    System.out.println(
        String.format(
            "%-" + LOGTIME_LENGTH + "s", formatTime(getSimulation().getSimulationTimeMillis()) + "  ID: " + getID()
        ) + "  " + log
    );
}




  public static String formatTime(long milliseconds) {
    long mins = milliseconds / 60000;
    long secs = (milliseconds % 60000) / 1000;
    long millis = milliseconds % 1000;

    String paddedSecs = String.format("%02d", secs);
    String paddedMillis = String.format("%03d", millis);

    return mins + ":" + paddedSecs + "." + paddedMillis;
  }


  @Override
  public void sentPacket(RadioPacket p) {
  }
  
  @Override
  public String toString() {
    return "P2P " + getID();
  }

  @Override
  public void writeArray(byte[] s) {}

  @Override
  public void writeByte(byte b) {}

  @Override
  public void writeString(String s) {}
}