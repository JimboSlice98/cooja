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

public class Peer2PeerMoteTTL extends AbstractApplicationMote {
  private ApplicationRadio radio;
  private Random rd;

  private static final long TRANSMISSION_DURATION = Simulation.MICROSECOND*300;  // Packet broadcast time: 300 μs
  private static final long SEND_INTERVAL = Simulation.MILLISECOND*1000*60;      // Send request every 60 seconds
  private static final long REQUEST_INTERVAL = Simulation.MILLISECOND*1000*60;   // Send request every 60 seconds
  
  private static final long MOTE_OFFSET = Simulation.MILLISECOND*1000;           // Each motes request will be offset by this time
  private static final long MS = Simulation.MILLISECOND;
  private static final long US = Simulation.MICROSECOND;

  private static final int PROCESS_DELAY_MEAN = 600;
  private static final int PROCESS_DELAY_UNCERTAINTY = 500;

  private static final int TTL = 8;
  private static final int LOG_LENGTH = 40;
  private static final int LOGTIME_LENGTH = 20;
  private static final int ACTION_LENGTH = 30;

  private long txCount = 0;
  private Map<String, String> messageCache = new HashMap<>();

  public enum Action {
    BROADCAST_MESSAGE,
    BROADCAST_ATTESTATION,
    RELAY_MESSAGE,
    RELAY_ATTESTATION
  }


  public Peer2PeerMoteTTL(MoteType moteType, Simulation simulation) throws MoteType.MoteTypeCreationException {
    super(moteType, simulation);
  }
  

  protected void execute(long time) {    
    if (radio == null) {
      radio = (ApplicationRadio) getInterfaces().getRadio();
      rd = getSimulation().getRandomGenerator();
    }

    if (getID() == 1) {
      schedulePeriodicPacket(-SEND_INTERVAL + 1000*MS);
    }

    // schedulePeriodicPacket(1000*MS*getID());
  }

  
  private void scheduleCacheRefresh() {
    getSimulation().scheduleEvent(new MoteTimeEvent(this) {
      @Override
      public void execute(long t) {
        messageCache.clear();
        scheduleCacheRefresh();
      }
    }, getSimulation().getSimulationTime() + MOTE_OFFSET*5);
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
      int ttl = Integer.parseInt(parts[3]) - 1;
      int fromNode = Integer.parseInt(parts[4]);

      String messageID = messageNum + "|" + originNode + "|" + attestNode;
      String logMsg = "Rx: '" + messageID + "' from node: '" + fromNode + "' with ttl: '" + ttl + "'";
      // logf(logMsg, null);

      int processingDelay = generateRandomDelay(PROCESS_DELAY_MEAN, PROCESS_DELAY_UNCERTAINTY);
      processingDelay = 0;

      if (attestNode == 0) {
        // logf(logMsg, null);
      }
      
      if (attestNode != 0 && originNode != getID()) {        
        scheduleBroadcastPacket(messageID, ttl, processingDelay, Action.RELAY_ATTESTATION);
        return;
      }
      else if (attestNode != 0) {
        if (messageCache.containsKey(messageID)) {
        }
        else {
          messageCache.put(messageID, "0");
          logf(logMsg, "attestation received");
        }
        return;        
      }
      
      // Handle incomming messages and generate attestation
      scheduleBroadcastPacket(messageID, ttl, processingDelay, Action.RELAY_MESSAGE);
      scheduleBroadcastPacket(messageNum + "|" + originNode + "|" + getID(), TTL, processingDelay, Action.BROADCAST_ATTESTATION);

    } catch (NumberFormatException e) {
      System.out.println("Mote " + getID() + " received bad data: " + e);
    }
  }


  private void schedulePeriodicPacket(long timeOffset) {
    getSimulation().scheduleEvent(new MoteTimeEvent(this) {
      @Override
      public void execute(long t) {        
        scheduleBroadcastPacket(txCount + "|" + getID() + "|" + 0, TTL, 0, Action.BROADCAST_MESSAGE);
        schedulePeriodicPacket(0);
      }
    }, getSimulation().getSimulationTime() + SEND_INTERVAL + timeOffset);
  }


  private void scheduleBroadcastPacket(String messageID, int ttl, int timeOffset, Action action) {
    if (ttl <= 0) {
      // System.out.println(formatTimeMicro(getSimulation().getSimulationTime()) + "  ID " + getID() + ": TTL expired for " + messageID);
      return;
    }
  
    getSimulation().scheduleEvent(new MoteTimeEvent(this) {
      @Override
      public void execute(long t) {
        if (!attemptBroadcast(messageID, ttl, action)) {
          // logf("Fx: '" + messageID + "|" + messageData, "rescheduled");
          scheduleBroadcastPacket(messageID, ttl, 100, action);
        }
      }
    }, getSimulation().getSimulationTime() + timeOffset * US);
  }

  
  private boolean attemptBroadcast(String messageID, int ttl, Action action) {    
    if (!radio.isTransmitting() && !radio.isReceiving() && !radio.isInterfered()) {
      
      switch (action) {
        case BROADCAST_MESSAGE:
          logf("Tx: " + "'" + messageID + "' with ttl: " + ttl + "'", null);
          txCount++;
          break;
        
        case BROADCAST_ATTESTATION:
          // logf("Ax: '" + messageID + "|" + messageData, null);
          break;
    
        case RELAY_MESSAGE:
        case RELAY_ATTESTATION:
          // logf("Bx: '" + messageID + "|" + messageData, action.toString().replace("_", " ").toLowerCase());
          break;
    
        default:
          System.out.println("handleBroadcastAction() error");
      }
    
      radio.startTransmittingPacket(new COOJARadioPacket((messageID + "|" + ttl + "|" + getID()).getBytes(StandardCharsets.UTF_8)), TRANSMISSION_DURATION);
      return true;
    }
    return false;
  }
  
  
  private void logf(String logMsg, String actionMsg) {
    String log;
    if (actionMsg != null) {
      log = String.format("%-" + LOG_LENGTH + "s", logMsg) + " -> " + actionMsg;
    } else {
      log = String.format("%-" + (LOG_LENGTH + ACTION_LENGTH) + "s", logMsg);
    }
    // if (channel != null) {
    //   log = String.format("%-" + (LOG_LENGTH + ACTION_LENGTH) + "s", log) + " Channel: " + channel;
    // }
    log(log);
    System.out.println(
      String.format(
          "%-" + LOGTIME_LENGTH + "s", formatTimeMicro(getSimulation().getSimulationTime()) + "  ID: " + getID()
      ) + "  " + log
    );
  }


  public static String formatTimeMilli(long milliseconds) {
    long mins = milliseconds / 60000;
    long secs = (milliseconds % 60000) / 1000;
    long millis = milliseconds % 1000;

    String paddedSecs = String.format("%02d", secs);
    String paddedMillis = String.format("%03d", millis);

    return mins + ":" + paddedSecs + "." + paddedMillis;
  }


  public static String formatTimeMicro(long microseconds) {
    long mins = microseconds / 60000000;
    long secs = (microseconds % 60000000) / 1000000;
    long millis = (microseconds % 1000000) / 1000;
    long micros = microseconds % 1000;

    String paddedSecs = String.format("%02d", secs);
    String paddedMillis = String.format("%03d", millis);
    String paddedMicros = String.format("%03d", micros);

    return mins + ":" + paddedSecs + "." + paddedMillis + "," + paddedMicros;
  }


  public int generateRandomDelay(int mean, int uncertainty) {
    int lowerBound = mean - uncertainty;
    int upperBound = mean + uncertainty;
    return rd.nextInt(upperBound - lowerBound + 1) + lowerBound;
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
