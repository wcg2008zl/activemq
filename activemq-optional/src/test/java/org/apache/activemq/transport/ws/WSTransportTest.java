/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.activemq.transport.ws;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;

import org.apache.activemq.broker.BrokerFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.SslContext;
import org.apache.activemq.spring.SpringSslContext;
import org.apache.activemq.transport.stomp.StompConnection;
import org.apache.activemq.util.Wait;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WSTransportTest {

    private static final Logger LOG = LoggerFactory.getLogger(WSTransportTest.class);
    private static final int MESSAGE_COUNT = 1000;

    private BrokerService broker;
    private Server server;
    private WebDriver driver;
    private File profileDir;

    private String stompUri;
    protected String wsUri;

    private StompConnection stompConnection = new StompConnection();

    protected BrokerService createBroker(boolean deleteMessages) throws Exception {
        BrokerService broker = BrokerFactory.createBroker(
                new URI("broker:()/localhost?persistent=false&useJmx=false"));

        SpringSslContext context = new SpringSslContext();
        context.setKeyStore("src/test/resources/server.keystore");
        context.setKeyStoreKeyPassword("password");
        context.setTrustStore("src/test/resources/client.keystore");
        context.setTrustStorePassword("password");
        context.afterPropertiesSet();
        broker.setSslContext(context);

        stompUri = broker.addConnector("stomp://localhost:0").getPublishableConnectString();
        wsUri = broker.addConnector(getWSConnectorURI()).getPublishableConnectString();
        broker.setDeleteAllMessagesOnStartup(deleteMessages);
        broker.start();
        broker.waitUntilStarted();

        return broker;
    }

    protected String getWSConnectorURI() {
        return "ws://127.0.0.1:61623?websocket.maxTextMessageSize=99999&transport.maxIdleTime=1001";
    }

    protected Server createWebServer() throws Exception {
        Server server = new Server();

        Connector connector = createJettyConnector();
        connector.setServer(server);

        WebAppContext context = new WebAppContext();
        context.setResourceBase("src/test/webapp");
        context.setContextPath("/");
        context.setServer(server);

        server.setHandler(context);
        server.setConnectors(new Connector[] {
                connector
        });
        server.start();
        return server;
    }

    protected Connector createJettyConnector() {
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(8080);
        return connector;
    }

    protected void stopBroker() throws Exception {
        if (broker != null) {
            broker.stop();
            broker.waitUntilStopped();
            broker = null;
        }
    }

    @Before
    public void setUp() throws Exception {
        profileDir = new File("activemq-data/profiles");
        broker = createBroker(true);
        stompConnect();
        server = createWebServer();
    }

    @After
    public void tearDown() throws Exception {
        try {
            stompDisconnect();
        } catch(Exception e) {
            // Some tests explicitly disconnect from stomp so can ignore
        } finally {
            stopBroker();
            if (driver != null) {
                driver.quit();
                driver = null;
            }
            if (server != null) {
                server.stop();
            }
        }
    }

    @Test
    public void testBrokerStart() throws Exception {
        assertTrue(broker.isStarted());
    }

    @Ignore
    @Test
    public void testFireFoxWebSockets() throws Exception {
        driver = createFireFoxWebDriver();
        doTestWebSockets(driver);
    }

    @Ignore
    @Test
    public void testChromeWebSockets() throws Exception {
        driver = createChromeWebDriver();
        doTestWebSockets(driver);
    }

    protected WebDriver createChromeWebDriver() throws Exception {
        File profile = new File(profileDir, "chrome");
        profile.mkdirs();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--enable-udd-profiles",
                             "--user-data-dir=" + profile,
                             "--allow-file-access-from-files");
        return new ChromeDriver(options);
    }

    protected WebDriver createFireFoxWebDriver() throws Exception {
        File profile = new File(profileDir, "firefox");
        profile.mkdirs();
        return new FirefoxDriver(new FirefoxProfile(profile));
    }

    private void stompConnect() throws IOException, URISyntaxException, UnknownHostException {
        URI connectUri = new URI(stompUri);
        stompConnection.open(createSocket(connectUri));
    }

    private Socket createSocket(URI connectUri) throws IOException {
        return new Socket("127.0.0.1", connectUri.getPort());
    }

    private void stompDisconnect() throws IOException {
        if (stompConnection != null) {
            stompConnection.close();
            stompConnection = null;
        }
    }

    protected String getTestURI() {
        return "http://localhost:8080/websocket.html#" + wsUri;
    }

    public void doTestWebSockets(WebDriver driver) throws Exception {

        driver.get(getTestURI());

        final WebElement webStatus = driver.findElement(By.id("status"));
        final WebElement webReceived = driver.findElement(By.id("received"));

        while ("Loading" == webStatus.getText()) {
            Thread.sleep(100);
        }

        // Skip test if browser does not support websockets..
        if (webStatus.getText() != "No WebSockets") {

            assertTrue("Should have connected", Wait.waitFor(new Wait.Condition() {

                @Override
                public boolean isSatisified() throws Exception {
                    return webStatus.getText().equals("Connected");
                }
            }));

            stompConnection.connect("system", "manager");

            stompConnection.send("/queue/websocket", "Hello");
            assertTrue("Should have received message by now.", Wait.waitFor(new Wait.Condition() {
                @Override
                public boolean isSatisified() throws Exception {
                    return webReceived.getText().equals("Hello");
                }
            }));

            for (int i = 1; i <= MESSAGE_COUNT; ++i) {
                stompConnection.send("/queue/websocket", "messages #" + i);
            }

            assertTrue("Should have received messages by now.", Wait.waitFor(new Wait.Condition() {
                @Override
                public boolean isSatisified() throws Exception {
                    return webReceived.getText().equals("messages #" + MESSAGE_COUNT);
                }
            }));
        }
    }
}
