# WebDAV Servlet (unmaintained)

*This repository will no longer be maintained. I recommend [Milton](https://milton.io/) instead*

A Servlet that brings basic WebDAV access to any store. Only one interface (IWebdavStorage) has to
be implemented, an example (LocalFileSystemStorage) which uses the local filesystem, is provided.
Unlike large systems (like slide), this servlet only supports the most basic data access options.
versioning or user management are not supported

This library is a fork of the WebDAV servlet library by Hendy Irawan (
GitHub: https://github.com/ceefour), which itself forked it from the original WebDAV servlet that
comes with Apache Tomcat. The IWebdavStorage is inspired by BasicWebdavStore of Oliver Zeigermann's
slide-WCK.

## Requirements

* Java 8
* A servlet container that supports at least Servlet Specification 4.0

## Installation and Configuration

### Spring Boot

Include the dependency:

```xml

<dependency>
    <groupId>org.drjekyll</groupId>
    <artifactId>webdav-servlet</artifactId>
    <version>3.0.0</version>
</dependency>
```

or

```groovy
implementation 'org.drjekyll:webdav-servlet:3.0.0'
```

Create a `ServletRegistrationBean` for the WebDAV servlet:

```java

@Configuration
public class WebdavConfig {

    @Bean
    public ServletRegistrationBean<WebdavServlet> exampleServletBean() {
        return new ServletRegistrationBean<>(new WebdavServlet(), "/webdav/*");
    }

}
```

### Servlet Container

* Download the JAR
  from https://repo1.maven.org/maven2/org/drjekyll/webdav-servlet/3.0.0/webdav-servlet-3.0.0.jar
* Place the webdav-servlet.jar in the /WEB-INF/lib/ of your webapp
* Open web.xml of the webapp. it needs to contain the following:

```xml
<!DOCTYPE web-app
    PUBLIC "-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN"
    "http://java.sun.com/dtd/web-app_2_3.dtd">

<web-app>

    <servlet>
        <servlet-name>webdav</servlet-name>
        <servlet-class>
            org.drjekyll.webdav.WebdavServlet
        </servlet-class>
        <init-param>
            <param-name>ResourceHandlerImplementation</param-name>
            <param-value>
                org.drjekyll.webdav.store.LocalFileSystemStore
            </param-value>
            <description>
                name of the class that implements
                org.drjekyll.webdav.store.WebdavStore
            </description>
        </init-param>
        <init-param>
            <param-name>rootpath</param-name>
            <param-value>/tmp/webdav</param-value>
            <description>
                local filesystem storage folder of the WebDAV content
            </description>
        </init-param>
        <init-param>
            <param-name>storeDebug</param-name>
            <param-value>0</param-value>
            <description>
                triggers debug output of the
                ResourceHandlerImplementation (0 = off , 1 = on) off by default
            </description>
        </init-param>
    </servlet>

    <servlet-mapping>
        <servlet-name>webdav</servlet-name>
        <url-pattern>/*</url-pattern>
    </servlet-mapping>

</web-app>
```

* If you want to use the reference implementation, set the parameter `rootpath` to where you want to
  store your files
* If you have implemented your own store, insert the class name to the
  parameter  `ResourceHandlerImplementation` and copy your .jar to /WEB-INF/lib/
* With /* as servlet mapping, every request to the webapp is handled by the servlet. change this if
  you want
* With the "storeDebug" parameter you can trigger the reference store implementation to spam at
  every method call. this parameter is optional and can be omitted
* Authentication is done by the servlet-container. If you need it, you have to add the appropriate
  sections to the web.xml

## Accessing the file store

The webdav-filestore is reached at

    http://<ip/name + port of the server>/<name of the webapp>/<servlet-maping>

e.g.:

    http://localhost:8080/webdav-servlet

## Changelog

### 3.0.0

* Forked and modernized the library and did an initial release

## Generate the site

To generate the Maven site, just execute

    mvn javadoc:jar site site:stage scm-publish:publish-scm
