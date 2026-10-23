make:
	javac -cp .:lib/* *.java

.PHONY: clean

clean:
	rm -rvf *.class
	./clean.sh
	rm -rvf *.rdb

resetdb:
	./clean.sh
	rm -rvf *.rdb