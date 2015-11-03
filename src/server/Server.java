/*
 * Server.java
 * Oct 7, 2012
 *
 * Simple Web Server (SWS) for CSSE 477
 * 
 * Copyright (C) 2012 Chandan Raj Rupakheti
 * 
 * This program is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License 
 * as published by the Free Software Foundation, either 
 * version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/lgpl.html>.
 * 
 */

package server;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;

import gui.WebServer;
import plugins.IPlugin;
import plugins.WatchDir;

/**
 * This represents a welcoming server for the incoming TCP request from a HTTP
 * client such as a web browser.
 * 
 * @author Chandan R. Rupakheti (rupakhet@rose-hulman.edu)
 */
public class Server implements Runnable {
	private String rootDirectory;
	private int port;
	private boolean stop;
	private ServerSocket welcomeSocket;
	private WatchDir wd;

	private long connections;
	private long serviceTime;

	private WebServer window;

	private Map<String, Queue<Thread>> requestMap;
	private Map<String, Integer> clientRequests;
	private List<String> bannedClients;
	private List<String> requesters;
	private List<String> tempRequesters;
	private Iterator<String> requestersItr;
	
	private String host;

	/**
	 * @param rootDirectory
	 * @param port
	 */
	public Server(String rootDirectory, int port, String host, WebServer window) {
		this.rootDirectory = rootDirectory;
		this.port = port;
		this.host = host;
		this.stop = false;
		this.connections = 0;
		this.serviceTime = 0;
		this.window = window;

		// performance improvement - queue
		this.requestMap = new HashMap<String, Queue<Thread>>();
		this.clientRequests = new HashMap<String, Integer>();
		this.bannedClients = new ArrayList<String>();
		this.requesters = new LinkedList<String>();
		this.tempRequesters = new LinkedList<String>();
		this.requestersItr = this.requesters.iterator();
	}

	/**
	 * Gets the root directory for this web server.
	 * 
	 * @return the rootDirectory
	 */
	public String getRootDirectory() {
		return rootDirectory;
	}

	/**
	 * Gets the port number for this web server.
	 * 
	 * @return the port
	 */
	public int getPort() {
		return port;
	}

	/**
	 * Returns connections serviced per second. Synchronized to be used in
	 * threaded environment.
	 * 
	 * @return
	 */
	public synchronized double getServiceRate() {
		if (this.serviceTime == 0)
			return Long.MIN_VALUE;
		double rate = this.connections / (double) this.serviceTime;
		rate = rate * 1000;
		return rate;
	}

	/**
	 * Increments number of connection by the supplied value. Synchronized to be
	 * used in threaded environment.
	 * 
	 * @param value
	 */
	public synchronized void incrementConnections(long value) {
		this.connections += value;
	}

	/**
	 * Increments the service time by the supplied value. Synchronized to be
	 * used in threaded environment.
	 * 
	 * @param value
	 */
	public synchronized void incrementServiceTime(long value) {
		this.serviceTime += value;
	}

	/**
	 * The entry method for the main server thread that accepts incoming TCP
	 * connection request and creates a {@link ConnectionHandler} for the
	 * request.
	 */
	public void run() {
		try {
			this.wd = new WatchDir();
			Thread t = new Thread(wd);
			t.start();
			
			Thread t2 = new Thread(new RequestHandler(this));
			t2.start();
			
			InetAddress host = InetAddress.getByName(this.host);
			this.welcomeSocket = new ServerSocket(port, 5, host);

			int counter = 0;
			long timer = System.currentTimeMillis();

			// Now keep welcoming new connections until stop flag is set to true
			while (true) {
				// Listen for incoming socket connection
				// This method block until somebody makes a request
				Socket connectionSocket = this.welcomeSocket.accept();
				
				// Come out of the loop if the stop flag is set
				if (this.stop)
					break;

				String key = "" + connectionSocket.getInetAddress();
				if (!this.bannedClients.contains(key)) {

					// Create a handler for this incoming connection and start
					// the handler in a new thread
					ConnectionHandler handler = new ConnectionHandler(this, connectionSocket);

					Thread thread = new Thread(handler);
//					thread.start();

					counter++;
					
					//This is a queue of requests for each client
					if (this.requestMap.containsKey(key)) {
						Queue<Thread> q = this.requestMap.get(key);
						q.add(thread);
						this.requestMap.put(key, q);
					} else {
						this.tempRequesters.add(key);
						Queue<Thread> q = new LinkedList<Thread>();
						q.add(thread);
						this.requestMap.put(key, q);
					}

					//This is the number of requests the client has made
					if (this.clientRequests.containsKey(key)) {
						int count = this.clientRequests.get(key);
						this.clientRequests.put(key, count + 1);
					} else {
						this.clientRequests.put(key, 1);
					}

				}
				if (System.currentTimeMillis() - timer > 1000) {
					System.out.println("Number of things served: " + counter);
					timer = System.currentTimeMillis();
					counter = 0;
					System.out.println("The number of banned clients is "+this.bannedClients.size());
					for (Entry<String, Integer> e : this.clientRequests.entrySet()) {
						if (e.getValue() > 50) {
							this.bannedClients.add(e.getKey());
							System.out.println(e.getKey() +" is now banned!");
						}
						this.clientRequests.put(e.getKey(), 0);
					}
				}
			}
			this.welcomeSocket.close();
		} catch (Exception e) {
			window.showSocketException(e);
		}
	}

	/**
	 * Stops the server from listening further.
	 */
	public synchronized void stop() {
		if (this.stop)
			return;

		// Set the stop flag to be true
		this.stop = true;
		try {
			// This will force welcomeSocket to come out of the blocked accept()
			// method
			// in the main loop of the start() method
			Socket socket = new Socket(InetAddress.getLocalHost(), port);

			// We do not have any other job for this socket so just close it
			socket.close();
		} catch (Exception e) {
		}
	}

	/**
	 * Checks if the server is stopped or not.
	 * 
	 * @return
	 */
	public boolean isStoped() {
		if (this.welcomeSocket != null)
			return this.welcomeSocket.isClosed();
		return true;
	}

	/**
	 * @return map of plugins
	 */
	public HashMap<String, IPlugin> getPlugins() {
		return wd.getPlugins();
	}

	public long getConnections() {
		return this.connections;
	}

	public void runNext() {
		System.out.println("Running next!");
		String key = null;
		if(this.requestersItr.hasNext())	
			key = this.requestersItr.next();
		else{
			this.requestersItr = null;
			if(!this.tempRequesters.isEmpty()) {
				this.requesters.addAll(this.tempRequesters);
				this.tempRequesters = new LinkedList<String>();
			}
			this.requestersItr = this.requesters.iterator();
			if (this.requestersItr.hasNext()) {
				key = this.requestersItr.next();
			}
		}
		if(key != null) {
			System.out.println("Going to run thread!");
			Queue<Thread> q = this.requestMap.get(key);
			Thread t = q.poll();
			t.start();
			if(q.isEmpty()){
				this.requesters.remove(key);
			}
		}
	}

	public int getNumberOfRequesters() {
		return this.requesters.size() + this.tempRequesters.size();
	}
}
