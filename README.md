# standalone-tomcat-micrometer-bug
Example of problem with Micrometer's built in instrumentation 
for Tomcat when deploying to standalone Tomcat

The problem is line 21 of this class: [MetricsServlet](https://github.com/plilja/standalone-tomcat-micrometer-bug/blob/main/src/main/java/se/plilja/MetricsServlet.java)

You don't really need to start the application to reproduce the 
problem. Since the problem occurs at compile time.

However, if you still want to start the application. 
You can either build using maven and deploy the war file
to a Tomcat instance. Or you can start using IntelliJ Enterprise
Edition using the following steps:

1. Download Tomcat and unpack somewhere on your hard drive
2. Open project structure and create an artifact for the exploded WAR file.
   - ![create-artifact](images/create_artifact.png)
3. It should look like this
   - ![create-artifact2](images/create_artifact2.png)
4. Create a new run config for a local Tomcat server
   - ![create-run-config](images/create_run_config.png)
5. Setup the Tomcat server you downloaded in step 1. Click red circle and enter the folder from step 1.
   - ![setup_tomcat_server.png](images/setup_tomcat_server.png)
6. In the "Before launch" section. Add the artifact from step 2.
   - ![before-launch](images/before_launch.png)
7. In the deployment section, change the application context to "/"
   - ![deployment](images/deployment.png)
8. Run, metrics endpoint can be found on http://localhost:8080/metrics