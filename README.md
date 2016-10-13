# netconf
Mirror of the OpenDaylight netconf gerrit project including yang-push support for netconf northbound server.

To get this implementation running just git clone (https://github.com/DarioSchwarzbach/netconf.git) the repository and 
run 'mvn clean install -DskipTests' to build the project (at the moment the build will fail if tests are not skipped!).
Afterwards start karaf by running the karaf script located in 'netconf/karaf/target/assembly/bin'. When karaf has successfully
started do 'feature:install odl-netconf-mdsal' to install netconf northbound server with included yang-push support (at the moment
do not install odl-netconf-all before odl-netconf-mdsal because it leads to an error and unsuccessful start of netconf northbound
server).
You want to check 'log:tail' to see if YangpushProvider has successfully registered.
Now you are set to establish periodic or on change yang-push subscriptions via NCClient or other netconf clients.

Please see also https://github.com/MBlahetek/ncclient for a related NETCONF client to test this implementation.
