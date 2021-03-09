#!/bin/bash

# THE GTFS FILES DEFINE SCHEDULED TRIPS FOR ALL 7 DAYS PER WEEK
# WE'LL SIMPLIFY THE PROJECT BY ONLY SUPPORTING A SINGLE WEEKDAY
#
DAY="4"		# mon-2, tue=3, ... sun=8

# EACH STATION'S TIMETABLE FILE IS NAMED, SAY, tt-Subiaco_Stn
#
TT="tt"
#
verbose=n

# ---------------------------------------------------------

PRE="/tmp/my"
TMPA="/tmp/tt-a$$"
TMPB="/tmp/tt-b$$"
TMPC="/tmp/tt-c$$"
#
function cleanup() {
    rm -f $TMPA $TMPB $TMPC
    rm -f $PRE-*
}
#
trap "cleanup ; exit 1" SIGINT SIGTERM
#
function check_gtfs_files() {
    for f in calendar routes stops stop_times trips ; do
	if [ ! -r $f.txt ]; then
	    echo "cannot read $f.txt"
	    exit 1
	fi
    done
}
#
function find_stop2name() {
    grep -m1 "^$1," < $PRE-stop_shortname | cut -d, -f2
}
#
function find_trip2busnumber() {
    grep -m1 "^$1," < $PRE-trip_busnumber | cut -d, -f2
}
#
function build_stop_shortname() {
    echo stop_id,stop_shortname > $PRE-stop_shortname
    grep '^[0-9]' < stops.txt		| \
	cut -d, -f3,5			| \
	sort -nu 			| \
	sed -e 's,",,g' -e 's,[/ -],_,g' -e 's,_$,,' | \
	tr -s _ >> $PRE-stop_shortname
}
#
function build_today() {
    cut -d, -f1,$DAY < calendar.txt	| \
	grep ',1$'			| \
	cut -d, -f1			| \
	sort > $PRE-service_today

    cut -d, -f1,2,3 < trips.txt 	| \
	sort -t, -k2 > $TMPA
    join -t, -2 2 $PRE-service_today $TMPA > $PRE-trips_today

    cut -d, -f3 < $PRE-trips_today | sort > $TMPA
    cut -d, -f1-5 < stop_times.txt | sort > $TMPB
    join -t, $TMPA $TMPB > $PRE-stop_times_today
    rm -f $TMPA $TMPB
}
#
function build_trip_busnumber() {
    echo trip_id,bus_number > $PRE-trip_busnumber

    cut -d, -f1-3 < trips.txt	| \
	sort -t, -k2 > $TMPA
    join -t, -2 2 -o 2.1,2.2,2.3 $PRE-service_today $TMPA | \
	sort > $TMPB
    cut -d, -f1-4 < routes.txt	| \
	sort > $TMPC
    join -t, -o 1.3,2.3,2.4 $TMPB $TMPC | \
	sed -e 's/,,/,/' -e 's/,$//' -e 's/ /_/g' | \
	sort -u >> $PRE-trip_busnumber
    rm -f $TMPA $TMPB $TMPC
}
#
function build_parent_stations() {
    echo parent_stop_id,stop_shortname,stop_lat,stop_lon > $PRE-parent_stations
    grep '^1,' < stops.txt	| \
	sed 's/Tce,/Tce/'	| \
	cut -d, -f3,5,7,8 > $TMPA
    while IFS=, read stop _ lat lon ; do
	NAME0=`find_stop2name $stop`
	echo "$stop,$NAME0,$lat,$lon"
    done < $TMPA >> $PRE-parent_stations
    rm -f $TMPA
}
#
function build_adjstops() {
    echo parent_stop_id,stop_id > $PRE-adjstops
    grep '^0,' < stops.txt	| \
	cut -d, -f2,3 		| \
	grep '^[0-9]' 		| \
	sort -n >> $PRE-adjstops
}
#
function build_timetables() {
    while IFS=, read parent NAME0 lat lon ; do
	if [ "$parent" == "parent_stop_id" ]; then
	    continue
	fi
	echo $NAME0
	echo "$NAME0,$lat,$lon" > $TT-$NAME0

#rm -f aaa
	for adjstop in `grep "^$parent," < $PRE-adjstops | cut -d, -f2`
	do
	    NAME1=`find_stop2name $adjstop`

#echo "adjstop=$adjstop" >> aaa
#echo "NAME0=$NAME0  (NAME1=$NAME1)" >> aaa
#grep -A 1 ":00,$adjstop," < $PRE-stop_times_today >> aaa

	    grep -A 1 ":00,$adjstop," < $PRE-stop_times_today | \
		cut -d, -f1-5		| \
		grep '^[0-9]'		| \
		sed  's/:00,/,/g'	> $TMPA

	    lc=1
	    while IFS=, read t1 arrives d1 stop seq ; do
		case $lc in
		1*)				# odd lines, involving adjstop
		    departs="$d1"
		    fromstop="$stop"
		    ;;
		0*)				# even lines, the next stop
		    if [ "$seq" != "1" ] ; then
			p1=`grep -m1 ",$stop\$" < $PRE-adjstops | cut -d, -f1`
			if [ "$p1" != "" ]; then	# has parent
			    NAMEf=`find_stop2name $fromstop`
			    NAME2=`find_stop2name $p1`
			    B=`find_trip2busnumber $t1`
			    echo "$departs,$B,$NAME1,$arrives,$NAME2"
#echo "dep=$departs t1=$t1 B=$B adjstop=$adjstop NAME1=$NAME1 fromstop=$fromstop NAMEf=$NAMEf -> arrives=$arrives stop=$stop p1=$p1 NAME2=$NAME2"
			fi
		    fi
		    ;;
		esac
		(( lc = 1 - lc ))
	    done < $TMPA
	done | sort -u | sort -k1n >> $TT-$NAME0

# REMOVE TIMETABLE FILES THAT HAVE NO BUSES LEAVING TODAY
	LC=`grep -c . < $TT-$NAME0`
	if [ "$LC" == "1" ] ; then
	    rm -f $TT-$NAME0
	fi
    done < $PRE-parent_stations
}
#
# ---------------------------------------------------------
#
function mywc() {
    echo "$1 -" `wc -l < $1`
}
function report_gtfs_files() {
    for i in *.txt ; do mywc $i  ; head -1 < $i | sed 's/^/    /' ; done
}
function report_my_files() {
    echo
    for i in $PRE-* ; do mywc $i ; head -2 < $i | sed 's/^/    /' ; done
}
#
# ---------------------------------------------------------
#
check_gtfs_files
build_stop_shortname
build_today
build_trip_busnumber
build_parent_stations
build_adjstops
build_timetables
#
if [ $verbose = y ]; then
    report_gtfs_files
    report_my_files
fi
#
cleanup
exit 0
