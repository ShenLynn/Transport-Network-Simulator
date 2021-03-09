import java.io.*;
import java.util.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channel;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.text.ParseException;
import java.text.SimpleDateFormat;

public class Station {
  private static String station_name;
  private static ArrayList<String> station_stops;
  private static Selector selector = null;
  private static HashMap<String, SocketAddress> neighbours;
  private static int udpportnumber;
  private static int tcpportnumber;
  private static DatagramChannel udpserver;

  public static void main(final String[] args) throws IOException, InterruptedException {
    station_name = args[0];
    tcpportnumber = Integer.parseInt(args[1]);
    udpportnumber = Integer.parseInt(args[2]);
    String route = "";

    neighbours = new HashMap<String, SocketAddress>();
    try {

      selector = Selector.open();
      final ServerSocketChannel tcpserver = ServerSocketChannel.open();
      tcpserver.bind(new InetSocketAddress("localhost", tcpportnumber));

      udpserver = DatagramChannel.open();
      udpserver.socket().bind(new InetSocketAddress("localhost", udpportnumber));

      tcpserver.configureBlocking(false);
      udpserver.configureBlocking(false);

      tcpserver.register(selector, SelectionKey.OP_ACCEPT);
      udpserver.register(selector, SelectionKey.OP_READ);
      Thread.sleep(1000); // sleep for 4 seconds, wait for other stations to startup to send name
      for (int i = 3; i < args.length; i++) {
        // send your stationname to each port number so they can store it
        SocketAddress stationadd = new InetSocketAddress("localhost", Integer.parseInt(args[i]));
        udp_send(udpserver, "stationname:" + station_name, stationadd);
      }

      while (true) {
        final int readychannel = selector.select();
        if (readychannel == 0)
          continue;
        final Set<SelectionKey> selectedkeys = selector.selectedKeys();
        final Iterator<SelectionKey> keyiterator = selectedkeys.iterator();
        while (keyiterator.hasNext()) {
          final SelectionKey key = keyiterator.next();
          final Channel c = key.channel();
          keyiterator.remove();
          if (!key.isValid()) {
            continue;
          }
          if (key.isReadable() && c == udpserver && key.isValid()) {

            // received message from adjacent stations
            final ByteBuffer message = ByteBuffer.allocate(1000);
            message.clear();
            SocketAddress clientAddress = udpserver.receive(message);
            message.flip();
            final int limit = message.limit();
            final byte m[] = new byte[limit];
            message.get(m, 0, limit);
            final String msg = new String(m, "US-ASCII");
            System.out.println(station_name + " received the query: " + msg);
            if (msg.contains("stationname:")) {
              // it's a station name
              String s = msg.split(":", 2)[1];
              neighbours.put(s, clientAddress);
            } else if (msg.contains("Arrival time:")) {
              // it's a finished route
              System.out.println(station_name + "Received client's route");
              route = msg;
            } else {
              // it's an unfinished route
              // check if this station is the destination
              String destination = msg.split(",")[0].split("\\s+")[2];
              if (destination.equals(station_name)) {
                String s[] = msg.split(",");
                SocketAddress firstStationadd = new InetSocketAddress(Integer.parseInt(s[0].split("\\s+")[0]));
                String arrivaltime = s[s.length - 1].split("-")[1].split("\\s+")[0];
                udp_send(udpserver, "Arrival time:" + arrivaltime + " " + msg, firstStationadd);
              } else {
                send_route(udpserver, msg);
              }
            }
          } else if (key.isAcceptable() && c == tcpserver) {
            System.out.println(station_name + "isacceptable");
            handleAccept(tcpserver, key);
          } else if (key.isReadable() && c != udpserver) {
            // receive query from tcp client
            System.out.println(station_name + "isreadable");
            handleRead(key);
          } else if (key.isWritable() && c != udpserver) {
            handleWrite(key, route);
          }
        }
      }
    } catch (final IOException e) {
      e.printStackTrace();
    }
  }

  // Finds an appropriate neighbour and sends nearest stop to that neighbour
  // Msg will be in format: "Startingport To stationB,Starting-10:00 East Station,
  // 9:15-10:30
  // tostationA, 10:40-10:50 tostationB"
  private static void send_route(final DatagramChannel mychannel, final String msg) {
    try {
      String stops[] = msg.split(",");
      // go through neighbours and look for one that hasn't been travelled to
      Iterator it = neighbours.entrySet().iterator();

      for (Map.Entry<String, SocketAddress> entry : neighbours.entrySet()) {
        String station = entry.getKey();
        SocketAddress stationaddress = entry.getValue();

        if (!travelled_before(stops, station)) {
          // current_time should be last stop's arrival time
          String current_time = stops[stops.length - 1].split("-")[1].split("\\s+")[0];
          System.out.println("Finding route for: " + station + "current time is: " + current_time);

          String earlieststop = find_earliest_stop(station, current_time);
          if (!earlieststop.equals("none")) {
            String updatedmsg = msg + "," + earlieststop;
            System.out.println(station_name + "sending the query: " + updatedmsg + "to  " + station);
            udp_send(mychannel, updatedmsg, stationaddress);
          } else {
            System.out.println("no route");
          }
        }
      }
    } catch (final IOException e) {
      e.printStackTrace();
    } catch (ParseException e) {
      e.printStackTrace();
    }
  }

  // loop through the stationlist and see if we've travelled to that station.
  private static boolean travelled_before(String[] stops, String station) {
    for (int i = 0; i < stops.length; i++) {
      if (stops[i].split("\\s+")[1].trim().equals(station)) {
        return true;
      }
    }
    return false;
  }

  // reads the timetable, and finds the earliest stop for that station with the
  // given time.
  // Return String format: stoptime-arrival arrivalstation
  private static String find_earliest_stop(String station, String time) throws ParseException {
    readfile(station_name + ".txt");
    SimpleDateFormat parser = new SimpleDateFormat("HH:mm");
    Date currenttime = parser.parse(time);
    for (int i = 0; i < station_stops.size(); i++) {
      String stopinfo[] = station_stops.get(i).split(",", 5);
      String arrivalstation = stopinfo[4].trim();
      Date stop_time = parser.parse(stopinfo[0]);
      if (arrivalstation.equals(station) && (stop_time.after(currenttime) || stop_time.equals(currenttime))) {
        // found a stop that is after the currenttime for that station
        return stopinfo[0] + "-" + stopinfo[3] + " " + arrivalstation;
      }
    }
    return "none";
  }

  // Sends a string to a udp server after encoding it
  private static void udp_send(final DatagramChannel mychannel, String message, SocketAddress address)
      throws CharacterCodingException {
    try {
      CharsetEncoder encoder = Charset.forName("US-ASCII").newEncoder();
      ByteBuffer udpmessage = ByteBuffer.allocate(1000);
      udpmessage.clear();
      udpmessage = encoder.encode(CharBuffer.wrap(message));
      mychannel.send(udpmessage, address);
    } catch (IOException e) {
      System.out.println("IO exception");
      e.printStackTrace();
    }
  }

  private static void readfile(final String filename) {
    try {
      station_stops = new ArrayList<String>();
      // first time reading timetable or updating timetable
      File timetable_file = new File(filename);
      final BufferedReader br = new BufferedReader(new FileReader(timetable_file));
      String st;
      br.readLine(); // ignore first line
      while ((st = br.readLine()) != null) {
        station_stops.add(st); // put the station stops in an array
      }
    } catch (final FileNotFoundException e) {
      System.out.println("File not found error");
      e.printStackTrace();
    } catch (final IOException e) {
      System.out.println("File not found error");
      e.printStackTrace();
    }
  }

  private static String get_current_time() {
    DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm");
    LocalDateTime now = LocalDateTime.now();
    System.out.println(dtf.format(now));
    return dtf.format(now);
  }

  private static void handleAccept(final ServerSocketChannel mySocket, final SelectionKey key) throws IOException {
    // Accept the connection and set non-blocking mode
    final SocketChannel client = mySocket.accept();
    if (client != null) {
      client.configureBlocking(false);
      System.out.println("Connection Accepted...");
      // Register that client is reading this channel
      client.register(selector, SelectionKey.OP_READ);
      final String response = "HTTP/1.1 200 OK\nContent-Type: text/html\nConnection: Closed\n\n<html><body><h1>Finding your requested route..</h1></body></html>";
      CharsetEncoder encoder = Charset.forName("US-ASCII").newEncoder();
      final ByteBuffer responsebuf = encoder.encode(CharBuffer.wrap(response));
      client.write(responsebuf);
    }
  }

  private static void handleRead(final SelectionKey key) throws IOException {
    System.out.println("Reading...");
    // create a ServerSocketChannel to read the request
    final SocketChannel client = (SocketChannel) key.channel();
    // Create buffer to read data
    final ByteBuffer buffer = ByteBuffer.allocate(400);
    client.read(buffer);
    // Parse data from buffer to String
    final String data = new String(buffer.array()).trim();
    if (data.length() > 0 && data.contains("GET /?to=")) {
      System.out.println("Received message: " + data);
      final String s[] = data.split("=", 2);
      String destination = s[1].substring(0, s[1].indexOf(" "));
      String msg = udpportnumber + " To " + destination + "," + "Starting-" + get_current_time() + " " + station_name;
      send_route(udpserver, msg);
      client.register(selector, SelectionKey.OP_WRITE);
    } else {
      client.close();
      System.out.println("connection closed");
    }
  }

  private static void handleWrite(final SelectionKey key, String route) throws CharacterCodingException {
    if (route != "") {
      final SocketChannel client = (SocketChannel) key.channel();
      CharsetEncoder encoder = Charset.forName("US-ASCII").newEncoder();
      final ByteBuffer responsebuf = encoder.encode(CharBuffer.wrap(route));
      try {
        client.write(responsebuf);
        client.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
}
