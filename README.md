# Project 3 Id Server Part 3

* Author: Alex Lewtschuk and  Kai Sorensen
* Class: CS455 [Distributed Systems] 

## Overview

PROJECT VIDEO: https://youtu.be/Ljfhd653CXw

This version implements the election algorithm, coordinator and replication.

## Manifest

IdServer: implements an RMI server
IdClient: implements a client that will call remote methods on the server
IdAccount: has the utility methods and stores the information for the account objects
ServerInterface: interface for the redis server
onyx.server: server input files with ip addresses

## Building the project

A makefile is included in the directory. It will compile the necessary files, preparing them for execution.
There are several ways to use our makefile.

Execute the makefile from the project directory as follows:

To compile all files use:
```
make
```

NOTE: THE FOLLOWING METHOD WILL KILL THE SERVER IF RUNNING!!!

To clean and reset the project(including the redis database) from the project directory, use:

```
make clean
```

To clear the redis database use:

```
make resetdb
```

## Features and usage

To use the program follow the following steps:

Start by launching the server. You can use the sh script that we designed. There are several ways to run the script.

First please ensure you allow the script execute permissions by running `chmod +x run-server.sh`.

To run the server in default mode (no optional commands used) use:
```
./run-server.sh
```

To run the server and specify the port number use:
```
./run-server.sh numport
```

To run the server with detailed verbose output use:
```
./run-server.sh verbose
```

To run the server with both optional commands enabled use:
```
./run-server.sh both
```

To run the server with the setup for Onyx use:
```
./run-server.sh onyx 5128
```

NOTE: Our program is configured to take .server files as input. Each of those files contains a list of IP addresses that are parsed by the program and iterated through till a connection is sucessfull. As the IPs in the provided onyx.server are configured for Boise State's Onyx system if you wish to run multiple server instances it is recommended to provide your own list of IP addresses of machines running server insances.

## Testing

A whole lotta debug output.

### Known Bugs

Cannot be run with localhost anyore due to how server handles setup.

Docker has a problem. We run everything as it should be ran on the server, but we were unable to get our docker image to setup redis. Until this problem is sovled, it can't be ran on redis and can only be ran on onyx. However, the code is flexible and will work on any network with unique IP addresses, virtual or not. We know this because the things that do not require redis will still run on Docker, but the Docker image itself cannot be created with redis support.

## Reflection

Kai:
Since Alex was sick at the beginning, I had to start the project myself and made some poor design choices. In retrospect, I wish I didn't use sockets for server-server communication. We had already implemented RMI for the client and it would have saved time to implement another RMI for server-server. After the sockets were implemented I was thinking that I had dug too deep a hole to switch back to RMI (since I was so close to having it working), but it still would have saved time to scrap the sockets and go with RMI. Well, maybe it's a valuable lesson to have done it the inconvenient way and recognize why it wasn't the best approach.

This was by far the most hours I've put into any project ever (except for B-Tree), but it was by not necessarily the most difficult. What drained my time was debugging. I have no idea how to debug server-server connections other than instructing the servers to always dump all of their data so that the state can be determined at each instant before an error. This is so difficult when the server errors but doesn't crash. Maybe writing testers would actually save time here. A tester would be able to precisely determine which things are going wrong. Next time I'm facing a project like this, I'll likely create some unit tests.

Overall it was one of the most fascinating projects I've ever worked on. From creating a chat-server to essentially running a server cluster on the cloud, I learned a lot about the design and implementation of the internet at large. Computer science is not about programming, it's about engineering better solutions to humanity's problems. This project captured that spirit.

Alex:
This project was something, that's for sure. Unfortunatley this time around our development was a bit more fragmented than the last part of the project and there was less pair programming being done. This was mostly because I was sick, and when I say sick I mean SICK, like 104.6 fever sick. This sickness resulted in me being out of commmision for the first half of us working on our code and Kai definitely carried this particular part of the assignment. I wish that I was able to work more on this code than I did, but I did do a decent amount in the client and assisted Kai with planning and design aspects of the code that he wrote. 

It was cool to see the servers be able to work on an election and be able to understand who was the leader. Election algorithms are something that have interested me, especially after working on an a quorum based system for my reserach paper at the REU last summer. While we did get this working it was not without its hickups and issues. We kept running into issues parsing the File in the client, specifically how the system waited for a denial of connection to be thrown for it to try the next ip address in the list. We tried multiple attempt to solve this including using an executable task but for some reasion I could not get that to fucntion as we wanted it to. While the new timeout worked the connection was still denied. We fixed the connection issue by adding a line that checked to see if there was a redis instance at that address and if not it would move to the next. We attempted to implement this with the timeout feature, but it would not recognize the correct Ip even with the new line this time arround. So we scrapped that and began testing the client on onyx and docker and the client was a lot faster and the timeout issue was essentially no longar an issue. Some server issues also persisted like how our coordinator decided he just suddenly didn't want to be employed anymore and stopped doing its job without another election, but that was resolved as well. It was a fun project but we were stressing to the last second. This was one of if not the best project that I have ever had the pleasure of working on and the class was fantastic as well; I wish there was a Distributed Systems 2 course if I am being honest. Seeing the code we wrote from the ground up become something this complex, detailed and function is incredibly rewarding.

## Sources used

We mainly referenced the RMI examples.
ChatGPT was used to clarify concepts, but never to generate code.