package org.contikios.cooja.motes;

import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.Random;
import java.util.Collections;

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

/**
 * Peer-to-peer mote
 *
 * @author James Helsby
 */

public class Peer2PeerMote extends AbstractApplicationMote {
  private ApplicationRadio radio;
  private Random rd;
  private Set<String> attestationBuffer = new HashSet<>();
  private Set<String> messageCache = new HashSet<>();
  private long txCount = 0;

  private static final long TRANSMISSION_DURATION = Simulation.MICROSECOND*300;  // Packet broadcast time: 300 μs
  private static final long REQUEST_INTERVAL = Simulation.MILLISECOND*1000*60;   // Send request every 5 minutes
  private static final long ATTEST_INTERVAL = TRANSMISSION_DURATION*10;          // How long to wait before combining attestation signatures (~5)
  private static final long MOTE_OFFSET = Simulation.MILLISECOND*1000;           // Each motes request will be offset by this time
  private static final long MS = Simulation.MILLISECOND;
  private static final long US = Simulation.MICROSECOND;

  private static final int MAX_RETRIES = 10;
  private static final int MAX_CHANNELS = 3;
  private static final int LOG_LENGTH = 40;
  private static final int LOGTIME_LENGTH = 25;
  private static final int ACTION_LENGTH = 30;
  
  public enum Type {
    REQUEST,
    RELAY,
    ATTESTATION
  }

  public class DataHolder {
    public String data;
    public DataHolder(String data) {
      this.data = data;
    }
  }


  public Peer2PeerMote(MoteType moteType, Simulation simulation) throws MoteType.MoteTypeCreationException {
    super(moteType, simulation);
  }
  

  protected void execute(long time) {    
    if (radio == null) {
      radio = (ApplicationRadio) getInterfaces().getRadio();
      rd = getSimulation().getRandomGenerator();
    }
        
    schedulePeriodicPacket(REQUEST_INTERVAL, MS*1000*getID(), Type.REQUEST);
    schedulePeriodicPacket(ATTEST_INTERVAL, 0, Type.ATTESTATION);
    scheduleCacheRefresh();
  }


  private void schedulePeriodicPacket(long sendInterval, long timeOffset, Type messageType) {
    getSimulation().scheduleEvent(new MoteTimeEvent(this) {
      @Override
      public void execute(long t) {
        String data = "";
        if (messageType == Type.REQUEST) {
          data = generatePacketData(messageType);
        }
        scheduleBroadcastPacket(data, MAX_RETRIES, 0, messageType);
        schedulePeriodicPacket(sendInterval, 0, messageType);
      }
    }, getSimulation().getSimulationTime() + sendInterval + timeOffset);
  }


  private String generatePacketData(Type messageType) {
    String data = "";
    switch (messageType) {
      case REQUEST:
        data = txCount + "|" + getID() + "|" + 0;
        break;

      case ATTESTATION:
        if (!attestationBuffer.isEmpty()) {
          data = String.join(",", attestationBuffer);
          attestationBuffer.clear();
        }
        break;
    }
    return data;
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
    String[] messages = data.split(",");
    int fromNode = Integer.parseInt(messages[messages.length - 1]);
    // String logMessage = "Rx: '" + Arrays.toString(Arrays.copyOfRange(messages, 0, messages.length - 1)) + "' from node: '" + fromNode + "'";

    for (String message : messages) {
      String[] parts = message.split("\\|");

      if (parts.length == 1) {
        continue;
      }

      try {
        long messageNum = Long.parseLong(parts[0]);
        int originNode = Integer.parseInt(parts[1]);
        int attestNode = Integer.parseInt(parts[2]);
        String messageID = messageNum + "|" + originNode + "|" + attestNode;        
      
        // Handle duplicate packets
        if (messageCache.contains(messageID)) {
          // logf(logMessage, "DUPLICATE", null);
          continue;
        }
        
        messageCache.add(messageID);
        String logMessage = "Rx: '" + messageID + "' from node: '" + fromNode + "'";

        // Handle incoming attestations
        if (attestNode != 0 && originNode != getID()) {        
          attestationBuffer.add(messageID);
          // logf(logMessage, null, null);
          continue;
        } else if (attestNode != 0) {
          logf(logMessage, "ATTESTATION RECEIVED", null);
          continue;
        }

        // logf(logMessage, null, null);
        
        // Handle incoming requests
        scheduleBroadcastPacket(messageID, 0, 0, Type.RELAY);

        // Generate attestation
        String attestationID = messageNum + "|" + originNode + "|" + getID();
        attestationBuffer.add(attestationID);        
        messageCache.add(attestationID);

      } catch (NumberFormatException e) {
        System.out.println("Mote " + getID() + " received bad data: " + e);
      }
    }
  }


  private void scheduleBroadcastPacket(String data, int ttl, int timeOffset, Type messageType) {
    if (ttl < 0 || (messageType == Type.ATTESTATION && attestationBuffer.isEmpty())) {
      return;
    }

    final DataHolder dataHolder = new DataHolder(data);

    getSimulation().scheduleEvent(new MoteTimeEvent(this) {
      @Override
      public void execute(long t) {
        if (!radio.isTransmitting() && !radio.isReceiving() && !radio.isInterfered()) {
          switch (messageType) {
            case REQUEST:
              logf("Tx: " + "'" + dataHolder.data + "'", messageType.toString(), null);
              messageCache.add(dataHolder.data);
              txCount++;
              break;

            case RELAY:
              // logf("Tx: " + "'" + dataHolder.data + "'", messageType.toString(), ttl);
              break;

            case ATTESTATION:
              dataHolder.data = generatePacketData(messageType);
              // logf("Tx: '" + dataHolder.data + "'", messageType.toString(), ttl);
              break;
          }

          radio.startTransmittingPacket(new COOJARadioPacket((dataHolder.data + "," + getID()).getBytes(StandardCharsets.UTF_8)), TRANSMISSION_DURATION);
          
          if (messageType == Type.REQUEST) {
            scheduleBroadcastPacket(data, ttl - 1, 100, messageType);
          }

        } else {
          scheduleBroadcastPacket(dataHolder.data, ttl, 100, messageType);
        }
      }
    }, getSimulation().getSimulationTime() + timeOffset * US);
  }
 
  
  private void logf(String logMessage, String actionMsg, Integer channel) {
    String log;
    if (actionMsg != null) {
      log = String.format("%-" + LOG_LENGTH + "s", logMessage) + " -> " + actionMsg;
    } else {
      log = String.format("%-" + (LOG_LENGTH + ACTION_LENGTH) + "s", logMessage);
    }
    if (channel != null) {
      log = String.format("%-" + (LOG_LENGTH + ACTION_LENGTH) + "s", log) + " Channel: " + channel;
    }
    log(log);
    System.out.println(
      String.format(
          "%-" + LOGTIME_LENGTH + "s", formatTime(getSimulation().getSimulationTime()) + "  ID: " + getID()
      ) + "  " + log
    );
  }


  public static String formatTime(long microseconds) {
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
