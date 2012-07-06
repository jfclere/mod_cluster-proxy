/**
 * JBoss, Home of Professional Open Source. Copyright 2012, Red Hat, Inc., and
 * individual contributors as indicated by the @author tags. See the
 * copyright.txt file in the distribution for a full listing of individual
 * contributors.
 * 
 * This is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 * 
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this software; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA, or see the FSF
 * site: http://www.fsf.org.
 */
package org.jboss.cluster.proxy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.jboss.cluster.proxy.logging.Logger;

/**
 * {@code ProxyMain}
 * 
 * Created on Jun 18, 2012 at 4:18:59 PM
 * 
 * @author <a href="mailto:nbenothm@redhat.com">Nabil Benothman</a>
 */
public class ProxyMain {

	private static final String DEFAULT_PROTOCOL = "org.apache.coyote.http11.Http11NioProtocol";
	private static final String DEFAULT_SCHEME = "http";
	private static WebConnectorService service;
	private static boolean running = true;
	private static final List<Thread> threads = new ArrayList<>();
	private static final Logger logger = Logger.getLogger(ProxyMain.class);
	private static final String CONFIG_PATH = "conf" + File.separatorChar + "config.properties";

	/**
	 * Create a new instance of {@code ProxyMain}
	 */
	public ProxyMain() {
		super();
	}

	/**
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		long time = System.currentTimeMillis();
		String java_home = System.getProperty("java.home");
		java_home = java_home.substring(0, java_home.length() - 3);

		System.out
				.println("\n=========================================================================\n");
		System.out.println("  JBoss Mod Cluster Proxy Bootstrap Environment\n");
		System.out.println("  JAVA_HOME: " + java_home + "\n");
		System.out.println("  JBOSS_CLUSER_HOME: " + System.getProperty("user.dir") + "\n");
		System.out
				.println("=========================================================================\n\n");

		// Loading configuration first

		try (FileInputStream fis = new FileInputStream(CONFIG_PATH)) {
			logger.info("Loading configuration");
			System.getProperties().load(fis);
		} catch (Throwable t) {
			logger.error("Unable to load configurations", t);
			System.exit(-1);
		}

		try {
			String protocol = System.getProperty("http-protocol", DEFAULT_PROTOCOL);
			String scheme = System.getProperty("scheme", DEFAULT_SCHEME);
			// Creating the web connector service
			service = new WebConnectorService(protocol, scheme);
			// configure the web connector service


			// Setting the address (host:port)
			InetSocketAddress address = null;
			int port = Integer.valueOf(System.getProperty("org.apache.tomcat.util.net.PORT", "8081"));
			String hostname = System.getProperty("org.apache.tomcat.util.net.ADDRESS", "0.0.0.0");
			address = (hostname == null) ? new InetSocketAddress(port) : new InetSocketAddress(
					hostname, port);
			service.setAddress(address);

			// TODO finish configuration setup
			
			
			// Starting the web connector service
			service.start();
		} catch (Throwable e) {
			logger.error("creating protocol handler error", e);
			System.exit(-1);
		}

		threads.add(new Thread(new Runnable() {

			@Override
			public void run() {
				while (running) {
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						// NOPE
					}
				}
			}
		}) {
			public void interrupt() {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					// NOPE
				}
				super.interrupt();
			}
		});

		threads.add(new Thread(new Runnable() {

			@Override
			public void run() {
				try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
					String line = null;
					while ((line = br.readLine()) != null) {
						if (line.isEmpty()) {
							continue;
						}
						if (line.equalsIgnoreCase("stop")) {
							running = false;
							break;
						} else {
							logger.error("Unknow command : " + line);
						}
					}
				} catch (IOException e) {
					logger.error(e.getMessage(), e);
				}
				System.exit(0);
			}
		}));

		time = System.currentTimeMillis() - time;
		logger.info("JBoss Mod Cluster Proxy started in " + time + "ms");
		// Add shutdown hook
		addShutdownHook();
		// start all threads
		startThreads();
	}

	private static void addShutdownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				try {
					long time = System.currentTimeMillis();
					logger.info("Stopping JBoss Mod Cluster Proxy....");
					running = false;
					service.stop();
					time = System.currentTimeMillis() - time;
					interruptThreads();
					logger.info("JBoss Mod Cluster Proxy stopped in " + time + "ms");
				} catch (Throwable e) {
					logger.fatal(e.getMessage(), e);
				}
			}
		});
	}

	/**
	 * @throws Exception
	 */
	private static void startThreads() throws Exception {
		for (Thread t : threads) {
			t.start();
		}
		for (Thread t : threads) {
			t.join();
		}
	}

	/**
	 * 
	 * @throws Exception
	 */
	private static void interruptThreads() throws Exception {
		for (Thread t : threads) {
			t.interrupt();
		}
	}

}
