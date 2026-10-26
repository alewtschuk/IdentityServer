# IdentityServer demo
#   make          compiles Java source
#   make up       builds the image and starts the server cluster in the background
#   make logs     follows logs from every server
#   make test     run the client test script against the cluster
#   make failover stop the coordinator and check a new one is elected (test-failover.sh)
#   make down     stop the cluster and remove its containers (and their data)
#   make clean    remove compiled classes and stops and removes cluster images

COMPOSE ?= docker-compose

build:
	javac -cp ".:lib/*" *.java

up:
	$(COMPOSE) up --build -d

logs:
	$(COMPOSE) logs -f

test:
	$(COMPOSE) exec client bash run-tests.sh

failover:
	./test-failover.sh

down:
	$(COMPOSE) down

clean:
	rm -f *.class && make down 

.PHONY: build up logs test failover down clean
