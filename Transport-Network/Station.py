import select
import socket
import sys
import Queue
import SocketServer
from datetime import datetime
import time

station_name = sys.argv[1]
tcpportnumber = int(sys.argv[2])
udpportnumber = int(sys.argv[3])
adjacent_stations = sys.argv[4:]
inputs = []
outputs = []
neighbours = {} #dictionary Stationname: portnumber
completedroute = ""
routes_sent = 0
noroutes_received = 0

print(station_name + "setting up tcp server")
tcpserver = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
tcpserver.setblocking(0)
tcpserver.bind(("localhost", tcpportnumber))
tcpserver.listen(1)
inputs.append(tcpserver)

print("starting udp server")
udpserver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
udpserver.setblocking(0)
udpserver.bind(("localhost", udpportnumber))
inputs.append(udpserver)

time.sleep(1) # wait for other stations to start before sending name
#send your station name to adjacent ports
for station in adjacent_stations:
  myname = "stationname:" + station_name
  udpserver.sendto(myname, ("localhost", int(station)))

def tcphandle_read(tcpclient):
  data = tcpclient.recv(1024)
  print ('received "%s" from %s' % (data, tcpclient.getpeername()))
  if "GET /?to=" in data:
    s = data.split("=", 2)
    destination = s[1][0:s[1].index(" ")]
    print("destination is :"+destination)
    msg = str(udpportnumber) + " To " + destination + "," + "Starting-" + str(get_current_time()) + " "+ station_name;
    print(msg)
    send_route(udpserver, msg)
    outputs.append(tcpclient)
  else:
    tcpclient.close()

def udphandle_read(udpserver):
  global completedroute
  global routes_sent
  global noroutes_received
  data,address = udpserver.recvfrom(1024)
  if "stationname:" in data:
    stationname = data.split(":")[1]
    neighbours[stationname] = address
  elif "Arrival time:" in data:
    print("Received client's route" + data)
    completedroute = data
  elif "No route:" in data:
    #data format - No route: 4004 To station a, starting-10:00 station b, 10:00-10:30 station c
    noroutes_received = noroutes_received + 1
    if noroutes_received == routes_sent:
      #every route this station sent out has returned with no route
      #check if this station is the first station
      print(data)
      firststation_add = int(data.split(",")[0].split()[2])
      if udpportnumber == firststation_add:
        print("It's the same")
        completedroute = "No route found"
        routes_sent = 0
        noroutes_received = 0
      else:
        send_noroute(udpserver, data)
  else:
    msg = data.split(",")
    #unfinished route, check if this station is the destination
    destination = msg[0].split()[2]
    if destination == station_name:
      firststation_add = int(msg[0].split()[0])
      arrival_time = msg[len(msg)-1].split("-")[1].split()[0]
      udpserver.sendto("Arrival time:" + arrival_time +" "+ data, ("localhost", firststation_add))
    else:
      send_route(udpserver, data)

def handle_write():
  if completedroute != "":
    print("Sending route to client")
    s.send("HTTP/1.1 200 OK\nContent-Type: text/html\nConnection: Closed\n\n<html><body><h1>Finding your requested route..</h1></body></html>");
    s.send(completedroute)
    outputs.remove(s)
    s.close
  else :
    pass

def send_route(socket, msg):
  found_route = False
  global routes_sent
  global noroutes_received
  stops = msg.split(",")[1:]
  for station in neighbours:
    if not has_travelled(station, stops):
      current_time = stops[len(stops)-1].split("-")[1].split()[0]
      earlieststop = find_earliest_stop(station, current_time)
      print(earlieststop)
      if not earlieststop == "none":
        updatedmsg = msg + "," + earlieststop
        socket.sendto(updatedmsg, (neighbours[station]))
        routes_sent = routes_sent + 1
        found_route = True
        print("Sending route" + updatedmsg + "to " + str(neighbours[station]))
  if found_route == False:
    send_noroute(socket, msg)
    routes_sent = routes_sent + 1
  
def find_earliest_stop(adjstation, time):
  print("finding earliest stop for:" + adjstation)
  with open(station_name + ".txt") as timetable:
    stops = timetable.readlines()
  current_time = datetime.strptime(time, "%H:%M")
  for stop in stops[1:]:
    stopinfo = stop.split(",")
    arrivalstation = stopinfo[4].strip()
    stop_time = datetime.strptime(stopinfo[0],"%H:%M")
    arrival_time = stopinfo[3]
    if arrivalstation == adjstation and (stop_time > current_time or stop_time == current_time):
      return stopinfo[0] + "-" + arrival_time + " " + arrivalstation
  return "none"
  
#given A string with the format- No route:4004 To station a, starting-10:00 station b, 10:00-10:30 station c
#find the station before the current one and pass the route to that station
def send_noroute(socket, route):
  routes = route.split(",")[1:]
  if len(routes) == 1:
    #starting station didn't find a route
    global completedroute
    completedroute = "No route found"
    return
  if not "No route: " in route:
    route = "No route: " + route
  for stop in routes:
    currentstation = stop.split()[1]
    if currentstation == station_name:
      i = routes.index(stop)
      previous_station = routes[i-1].split()[1]
      print(previous_station)
      socket.sendto(route, (neighbours[previous_station]))

def has_travelled(station, stops):
  for stop in stops:
    currentstation = stop.split()[1]
    if station == currentstation:
      return True
  return False

def get_current_time():
  now = datetime.now()
  current_time = now.strftime("%H:%M")
  return current_time

#selector loop
while True: 
  readable, writable, exceptional = select.select(inputs, outputs, inputs)
  for s in readable:
    if s is tcpserver:
      print("tcp handling accept")
      (connection, client_address) = tcpserver.accept()
      connection.setblocking(0)
      inputs.append(connection) #add it to be able to read
    elif s is udpserver:
      udphandle_read(udpserver)
    else:
      tcphandle_read(s)
      
  for s in writable:
    handle_write()
    

