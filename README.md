# Project 2 Id Server

* Author: Kai Sorensen and Alex Lewtschuk
* Class: CS455 [Distributed Systems] Section #001

## Overview

PROJECT VIDEO: https://youtu.be/OnBDNEW4_V0

This project consists of two main programs: IdServer and IdClient. IdServer sets up an RMI instance and initializes a registry so that IdClient can use remote method calls to perform actions on the server. This is essentially a modified simple implementation of the Kerberos protocol.

## Manifest

IdServer: implements an RMI server
IdClient: implements a client that will call remote methods on the server
IdAccount: has the utility methods and stores the information for the account objects

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

## Testing

<!-- For this testing we essentially did end user testing. We thought of a list of what the user could do when running the server and then proceeded to test running different combinations of commands with different number of users on the server. This allowed us to catch most of the edge cases that we could think of. We ran commands when the client was connected to the server and when it was disconnected. The edge case generation that we did took care of all the issues that we ran into and the checks that we have implemented into our code took care of them all. -->

### Known Bugs

<!-- There are certainly some edge cases that we haven't yet discovered. However, if the user uses IRC
protocol properly, there shouldn't be any errors. -->

## Reflection

Kai:
First, the most valuable lesson: in learning from our previous project, we took a much more collaborative approach to our development this time around. To remind you, our last project consisted almost entirely of two files, comparable in their length and complexity. I developed one, and Alex developed the other. We did much better with P2. We each made significant contributions to every file, growing our understanding of the project as a team. We always knew what the other was doing and how it contributed to the development. I expect that we'll continue to improve our teamwork going forward, as more teamwork leads to higher productivity and less time tracking errors. I'd presume that in industry it helps to have some sort of chemistry with your partner, like a programming equivalent of social chemistry.

The biggest difficulty of this assignment was not developing the software. It was setting up the environment and build! We celebrated much more when we got our server to initially run without crashing than when we finished it. It was so annoying. Though, once we figured out how it worked, Alex wrote a great makefile for us that streamlined the build process and allowed us to begin truly implementing the project. I can see now how nightmarish dependencies can become. I wonder how dependencies are managed in industrial scale applications. There are probably people solely responsible for ensuring that the dependencies are compatible and legal as software and laws change.

As far as the engineering goes, we had some annoying bugs but nothing we couldn't handle. I feel that I grew my understanding of both the details and broad picture of system design. Learning how a login system could work was fascinating, I've always wondered how websites handle my password, and I've specifically wondered if some websites have a weaker password system than others. Let's say I use the same password for everything (and I don't exactly do this). If one weak website exposed the password, then that login information would be compromised for all of the stronger websites too. In our case we made the password unreadable, but the only reason it would be secure would be from the SSL encryption. Otherwise, an attacker could just send a packet with the encrypted password and get in just as readily. My understanding of this still feels incomplete, as I am not fully confident in this system design.




Alex:
Like Kai mentioned before we did a great job ensuring that we were pair programming more and working more logic based than file based. I personally belive that this enhanced our understanding of eachothers code and the project as a whole allowing us to work more effiencently and understand implementation better. There was definitely a decrease in bugs and roadblocks compared to the last project. 

This project was eye opening as it allowed me to better understand differnt methods of how servers and clients can interact. RMI was originally confusing for me and took me a moment to wrap my head arround, that is before we started working on the server and client. There was a little speedbump in learning how to parse and use the command line arguments due to learning the apache commons cli library, but now that that is under the belt it will help imensely going forward. One of the biggest issues that I ran into, and I belive that Kai did as well, was configuring the development environment. First we ran into issues with the package structure of our code and then with the dependancies and how they were handled. I can completly understand how and why industry prefers using build systems to handle them. I tried setting up gradle but it did not work. My largest issue was that my machine will not run RMI propperly and bind the registry. This seems to be an issue only with the eduroam network and I'm not sure why that happens. 

This project was so much fun. It was cool to see how a login system worked under the hood as that was always something that I've been curious about, especially for personal projects. I know that this project is going to be expanded upon going forward and I am curious to see where this goes and how we can build on this foundation going forward. 

## NOTE
We are using a late day :)

## Sources used

We mainly referenced the RMI examples.
ChatGPT was used to clarify concepts, but never to generate code.