/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.coyote.http11;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.apache.catalina.http.HttpResponseParser;
import org.apache.coyote.ActionCode;
import org.apache.coyote.Request;
import org.apache.coyote.RequestInfo;
import org.apache.coyote.Response;
import org.apache.coyote.http11.filters.BufferedInputFilter;
import org.apache.coyote.http11.filters.ChunkedInputFilter;
import org.apache.coyote.http11.filters.ChunkedOutputFilter;
import org.apache.coyote.http11.filters.GzipOutputFilter;
import org.apache.coyote.http11.filters.IdentityInputFilter;
import org.apache.coyote.http11.filters.IdentityOutputFilter;
import org.apache.coyote.http11.filters.SavedRequestInputFilter;
import org.apache.coyote.http11.filters.VoidInputFilter;
import org.apache.coyote.http11.filters.VoidOutputFilter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.HexUtils;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;
import org.apache.tomcat.util.net.NioEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SocketStatus;
import org.jboss.logging.Logger;

/**
 * {@code Http11NioProcessor}
 * <p>
 * Processes HTTP requests.
 * </p>
 * 
 * Created on Feb 22, 2012 at 3:00:29 PM
 * 
 * @author <a href="mailto:nbenothm@redhat.com">Nabil Benothman</a>
 */
public class Http11NioProcessor extends AbstractHttp11Processor<NioChannel> {

	protected static Logger log = Logger.getLogger(Http11NioProcessor.class);

	/**
	 * Input.
	 */
	protected InternalNioInputBuffer inputBuffer = null;

	/**
	 * Output.
	 */
	protected InternalNioOutputBuffer outputBuffer = null;

	/**
	 * Channel associated with the current connection.
	 */
	protected NioChannel channel;

	/**
	 * Associated endpoint.
	 */
	protected NioEndpoint endpoint;

	protected Http11NioProtocol http11Protocol;

	/**
	 * Create a new instance of {@code Http11NioProcessor}
	 * 
	 * @param headerBufferSize
	 * @param endpoint
	 */
	public Http11NioProcessor(int headerBufferSize, NioEndpoint endpoint) {
		this.endpoint = endpoint;
		this.request = new Request();
		this.inputBuffer = new InternalNioInputBuffer(this.request, headerBufferSize, endpoint);

		this.inputBuffer.setMaxPostSize(this.endpoint.getMaxPostSize());

		this.request.setInputBuffer(this.inputBuffer);

		this.response = new Response();
		this.response.setResponseParser(new HttpResponseParser());
		this.response.setHook(this);
		this.outputBuffer = new InternalNioOutputBuffer(this.response, headerBufferSize, endpoint);
		response.setOutputBuffer(outputBuffer);
		request.setResponse(response);
		sslEnabled = endpoint.getSSLEnabled();
		initializeFilters();

		// Cause loading of HexUtils
		@SuppressWarnings("unused")
		int foo = HexUtils.DEC[0];

		// Cause loading of FastHttpDateFormat
		FastHttpDateFormat.getCurrentDate();
	}

	/**
	 * Mark the start of processing
	 */
	public void startProcessing() {
		eventProcessing = true;
	}

	/**
	 * Mark the end of processing
	 */
	public void endProcessing() {
		eventProcessing = false;
	}

	/**
	 * @return true if the input buffer is available
	 */
	public boolean isAvailable() {
		return inputBuffer.available();
	}

	/**
	 * Add input or output filter.
	 * 
	 * @param className
	 *            class name of the filter
	 */
	protected void addFilter(String className) {
		try {
			Class<?> clazz = Class.forName(className);
			Object obj = clazz.newInstance();
			if (obj instanceof InputFilter) {
				inputBuffer.addFilter((InputFilter) obj);
			} else if (obj instanceof OutputFilter) {
				outputBuffer.addFilter((OutputFilter) obj);
			} else {
				log.warn(sm.getString("http11processor.filter.unknown", className));
			}
		} catch (Exception e) {
			log.error(sm.getString("http11processor.filter.error", className), e);
		}
	}

	/**
	 * General use method
	 * 
	 * @param sArray
	 *            the StringArray
	 * @param value
	 *            string
	 */
	protected String[] addStringArray(String sArray[], String value) {
		String[] result = null;
		if (sArray == null) {
			result = new String[1];
			result[0] = value;
		} else {
			result = new String[sArray.length + 1];
			System.arraycopy(sArray, 0, result, 0, sArray.length);
			// for (int i = 0; i < sArray.length; i++)
			// result[i] = sArray[i];
			result[sArray.length] = value;
		}
		return result;
	}

	/**
	 * 
	 * @param channel
	 */
	private void setChannel(NioChannel channel) {
		// Setting up the channel
		this.channel = channel;
		this.inputBuffer.setChannel(channel);
		this.outputBuffer.setChannel(channel);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.coyote.http11.Http11AbstractProcessor#awaitForNext()
	 */
	public void awaitNext() {

		final NioChannel ch = this.channel;
		// Asynchronous wait for next request.
		ch.awaitRead(endpoint.getKeepAliveTimeout(), TimeUnit.MILLISECONDS, ch,
				new CompletionHandler<Integer, NioChannel>() {

					@Override
					public void completed(Integer nBytes, NioChannel attachment) {
						if (nBytes < 0) {
							// Reach the end of the stream
							failed(new ClosedChannelException(), attachment);
						} else {
							// Process channel
							endpoint.processChannel(attachment, null);
							// Recycle the processor
							recycle();
						}
					}

					@Override
					public void failed(Throwable exc, NioChannel attachment) {
						// Close the channel and recycle the processor
						closeSocket(attachment);
					}
				});
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.apache.coyote.http11.Http11AbstractProcessor#event(org.apache.tomcat
	 * .util.net.SocketStatus)
	 */
	public SocketState event(SocketStatus status) throws IOException {
		// The event mode is not supported
		return SocketState.CLOSED;
	}

	/**
	 * Process pipelined HTTP requests using the specified input and output
	 * streams.
	 * 
	 * @param channel
	 * @return the process state
	 * 
	 * @throws IOException
	 *             error during an I/O operation
	 */
	public SocketState process(NioChannel channel) throws IOException {

		RequestInfo rp = request.getRequestProcessor();
		rp.setStage(org.apache.coyote.Constants.STAGE_PARSE);

		this.reset();
		// Setting up the channel
		this.setChannel(channel);

		int keepAliveLeft = maxKeepAliveRequests;
		int soTimeout = endpoint.getSoTimeout();
		boolean keptAlive = false;

		while (!error && keepAlive && !event) {

			// Parsing the request header
			try {
				if (!disableUploadTimeout && keptAlive && soTimeout > 0) {
					endpoint.setSoTimeout(soTimeout * 1000);
				}

				if (!inputBuffer.parseRequestLine(keptAlive)) {
					// This means that no data is available right now
					// (long keep-alive), so that the processor should be
					// recycled and the method should return true

					break;
				}

				request.setStartTime(System.currentTimeMillis());
				keptAlive = true;
				if (!disableUploadTimeout) {
					endpoint.setSoTimeout(timeout * 1000);
				}
				// Parsing headers
				inputBuffer.parseHeaders();
			} catch (IOException e) {
				error = true;
				break;
			} catch (Throwable t) {
				if (log.isDebugEnabled()) {
					log.debug(sm.getString("http11processor.header.parse"), t);
				}
				// 400 - Bad Request
				response.setStatus(400);
				error = true;
			}
			// Setting up filters, and parse some request headers
			rp.setStage(org.apache.coyote.Constants.STAGE_PREPARE);
			try {
				prepareRequest();
			} catch (Throwable t) {
				if (log.isDebugEnabled()) {
					log.debug(sm.getString("http11processor.request.prepare"), t);
				}
				// 500 - Internal Server Error
				response.setStatus(500);
				error = true;
			}

			if (maxKeepAliveRequests > 0 && --keepAliveLeft == 0) {
				keepAlive = false;
			}
			// Process the request in the adapter
			if (!error) {
				try {
					rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
					adapter.service(request, response);
					// Handle when the response was committed before a serious
					// error occurred. Throwing a ServletException should both
					// set the status to 500 and set the errorException.
					// If we fail here, then the response is likely already
					// committed, so we can't try and set headers.
					if (keepAlive && !error) { // Avoid checking twice.
						error = response.getErrorException() != null
								|| statusDropsConnection(response.getStatus());
					}
				} catch (InterruptedIOException e) {
					log.error(e.getMessage());
					error = true;
				} catch (Throwable t) {
					log.error(t.getMessage());
					log.error(sm.getString("http11processor.request.process"), t);
					// 500 - Internal Server Error
					response.setStatus(500);
					error = true;
				}
				//break;
			}

			if (error) {
				inputBuffer.setSwallowInput(false);
			}
		}

		if (error) {
			rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);
			endRequest();
			nextRequest();
			recycle();
			return SocketState.CLOSED;
		}

		return SocketState.OPEN;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.coyote.http11.Http11AbstractProcessor#endRequest()
	 */
	public void endRequest() {
		request.getRequestProcessor().setStage(org.apache.coyote.Constants.STAGE_ENDED);
		// Finish the handling of the request
		try {
			inputBuffer.endRequest();
		} catch (IOException e) {
			error = true;
		} catch (Throwable t) {
			log.error(sm.getString("http11processor.request.finish"), t);
			// 500 - Internal Server Error
			response.setStatus(500);
			error = true;
		}

		try {
			outputBuffer.endRequest();
		} catch (IOException e) {
			error = true;
		} catch (Throwable t) {
			log.error(sm.getString("http11processor.response.finish"), t);
			error = true;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.coyote.http11.Http11AbstractProcessor#nextRequest()
	 */
	public void nextRequest() {
		inputBuffer.nextRequest();
		outputBuffer.nextRequest();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.coyote.http11.Http11AbstractProcessor#recycle()
	 */
	public void recycle() {
		inputBuffer.recycle();
		outputBuffer.recycle();
		this.channel = null;
		super.recycle();
		this.http11Protocol.recycleProcessor(this);
	}

	/**
	 * Commit the action
	 * 
	 * @param param
	 */
	private void commit(Object param) {
		if (!response.isCommitted()) {
			// Validate and write response headers
			prepareResponse();
			try {
				outputBuffer.commit();
			} catch (IOException e) {
				// Set error flag
				error = true;
			}
		}
	}

	/**
	 * Send a 100 status back if it makes sense (response not committed yet, and
	 * client specified an expectation for 100-continue)
	 * 
	 * @param param
	 */
	private void sendAck(Object param) {

		if ((response.isCommitted()) || !expectation) {
			return;
		}

		inputBuffer.setSwallowInput(true);
		try {
			outputBuffer.sendAck();
		} catch (Exception e) {
			// Set error flag
			error = true;
		}
	}

	/**
	 * Flush the output buffer
	 */
	private void flush() {
		try {
			outputBuffer.flush();
		} catch (IOException e) {
			// Set error flag
			error = true;
			response.setErrorException(e);
		}
	}

	/**
	 * End the processing of the current request, and stop any further
	 * transactions with the client
	 */
	private void close() {
		event = false;
		try {
			outputBuffer.endRequest();
		} catch (IOException e) {
			// Set error flag
			error = true;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.coyote.http11.Http11AbstractProcessor#closeSocket()
	 */
	public void closeSocket() {
		closeSocket(this.channel);
	}

	/**
	 * 
	 * @param ch
	 */
	public void closeSocket(NioChannel ch) {
		if (ch != null) {
			endpoint.close(ch);
		}
		if (ch == this.channel) {
			this.recycle();
		}
	}

	/**
	 * Get the remote host address
	 */
	private void requestHostAddressAttr() {
		if (remoteAddr == null && (channel != null)) {
			try {
				remoteAddr = ((InetSocketAddress) this.channel.getRemoteAddress()).getHostName();
			} catch (Exception e) {
				log.warn(sm.getString("http11processor.socket.info"), e);
			}
		}
		request.remoteAddr().setString(remoteAddr);
	}

	/**
	 * Request the local name attribute
	 */
	private void requestLocalNameAttr() {
		if (localName == null && (channel != null)) {
			try {
				localName = ((InetSocketAddress) this.channel.getLocalAddress()).getHostName();
			} catch (Exception e) {
				log.warn(sm.getString("http11processor.socket.info"), e);
			}
		}
		request.localName().setString(localName);
	}

	/**
	 * Get remote host name
	 */
	private void requestHostAttribute() {
		if (remoteHost == null && (channel != null)) {
			try {
				remoteHost = ((InetSocketAddress) this.channel.getRemoteAddress()).getHostName();
				if (remoteHost == null) {
					remoteAddr = ((InetSocketAddress) this.channel.getRemoteAddress()).getAddress()
							.getHostAddress();
					remoteHost = remoteAddr;
				}
			} catch (Exception e) {
				log.warn(sm.getString("http11processor.socket.info"), e);
			}
		}
		request.remoteHost().setString(remoteHost);
	}

	/**
	 * Get local host address
	 */
	private void requestLocalHostAddressAttr() {
		if (localAddr == null && (channel != null)) {
			try {
				localAddr = ((InetSocketAddress) this.channel.getLocalAddress()).getAddress()
						.getHostAddress();
			} catch (Exception e) {
				log.warn(sm.getString("http11processor.socket.info"), e);
			}
		}

		request.localAddr().setString(localAddr);
	}

	/**
	 * Get remote port
	 */
	private void requestRemotePortAttr() {
		if (remotePort == -1 && (channel != null)) {
			try {
				remotePort = ((InetSocketAddress) this.channel.getRemoteAddress()).getPort();
			} catch (Exception e) {
				log.warn(sm.getString("http11processor.socket.info"), e);
			}
		}
		request.setRemotePort(remotePort);
	}

	/**
	 * Get local port
	 */
	private void requestLocalPortAttr() {
		if (localPort == -1 && (channel != null)) {
			try {
				localPort = ((InetSocketAddress) this.channel.getLocalAddress()).getPort();
			} catch (Exception e) {
				log.warn(sm.getString("http11processor.socket.info"), e);
			}
		}
		request.setLocalPort(localPort);
	}

	/**
	 * Get the SSL attribute
	 */
	private void requestSSLAttr() {
		try {
			if (sslSupport != null) {
				Object sslO = sslSupport.getCipherSuite();
				if (sslO != null)
					request.setAttribute(SSLSupport.CIPHER_SUITE_KEY, sslO);
				sslO = sslSupport.getPeerCertificateChain(false);
				if (sslO != null)
					request.setAttribute(SSLSupport.CERTIFICATE_KEY, sslO);
				sslO = sslSupport.getKeySize();
				if (sslO != null)
					request.setAttribute(SSLSupport.KEY_SIZE_KEY, sslO);
				sslO = sslSupport.getSessionId();
				if (sslO != null)
					request.setAttribute(SSLSupport.SESSION_ID_KEY, sslO);
			}
		} catch (Exception e) {
			log.warn(sm.getString("http11processor.socket.ssl"), e);
		}
	}

	/**
	 * Get the SSL certificate
	 */
	private void requestSSLCertificate() {
		if (sslSupport != null) {
			// Consume and buffer the request body, so that it does not
			// interfere with the client's handshake messages
			if (maxSavePostSize != 0) {
				BufferedInputFilter buffredInputFilter = new BufferedInputFilter();
				buffredInputFilter.setLimit(maxSavePostSize);
				inputBuffer.addActiveFilter(buffredInputFilter);
			}
			try {
				Object sslO = sslSupport.getPeerCertificateChain(true);
				if (sslO != null) {
					request.setAttribute(SSLSupport.CERTIFICATE_KEY, sslO);
				}
			} catch (Exception e) {
				log.warn(sm.getString("http11processor.socket.ssl"), e);
			}
		}
	}

	/**
	 * 
	 * @param param
	 */
	private void requestSetBodyReplay(Object param) {
		ByteChunk body = (ByteChunk) param;

		InputFilter savedBody = new SavedRequestInputFilter(body);
		savedBody.setRequest(request);

		InternalNioInputBuffer internalBuffer = (InternalNioInputBuffer) request.getInputBuffer();
		internalBuffer.addActiveFilter(savedBody);
	}

	/**
	 * Begin an event
	 * 
	 * @param param
	 *            the vent parameter
	 */
	private void beginEvent(Object param) {
		event = true;
		// Set channel to non blocking mode
		if (param == Boolean.TRUE) {
			outputBuffer.setNonBlocking(true);
			inputBuffer.setNonBlocking(true);
		}
	}

	/**
	 * End the event
	 * 
	 * @param param
	 *            the event parameter
	 */
	private void endEvent(Object param) {
		event = false;
		// End non blocking mode
		if (outputBuffer.getNonBlocking()) {
			outputBuffer.setNonBlocking(false);
			inputBuffer.setNonBlocking(false);
		}
	}

	/**
	 * Resume the event
	 * 
	 * @param param
	 *            the vent parameter
	 */
	private void resumeEvent(Object param) {
		readNotifications = true;
		// An event is being processed already: adding for resume will be
		// done
		// when the channel gets back to the poller
		if (!eventProcessing && !resumeNotification) {
			// endpoint.addEventChannel(channel, keepAliveTimeout, false, false,
			// true, true);
		}
		resumeNotification = true;
	}

	/**
	 * Write Event
	 * 
	 * @param param
	 */
	private void writeEvent(Object param) {
		// An event is being processed already: adding for write will be
		// done
		// when the channel gets back to the poller
		if (!eventProcessing && !writeNotification) {
			// endpoint.addEventChannel(channel, timeout, false, true, false,
			// true);
		}
		writeNotification = true;
	}

	/**
	 * Suspend Event
	 */
	private void suspendEvent() {
		readNotifications = false;
	}

	/**
	 * Timeout event
	 * 
	 * @param param
	 *            the timeout value
	 */
	private void timeoutEvent(Object param) {
		timeout = ((Integer) param).intValue();
	}

	/**
	 * Make the input buffer available
	 */
	private void makeAvailable() {
		inputBuffer.useAvailable();
	}

	/**
	 * Getter for http11Protocol
	 * 
	 * @return the http11Protocol
	 */
	public Http11NioProtocol getHttp11Protocol() {
		return this.http11Protocol;
	}

	/**
	 * Setter for the http11Protocol
	 * 
	 * @param http11Protocol
	 *            the http11Protocol to set
	 */
	public void setHttp11Protocol(Http11NioProtocol http11Protocol) {
		this.http11Protocol = http11Protocol;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.coyote.ActionHook#action(org.apache.coyote.ActionCode,
	 * java.lang.Object)
	 */
	public void action(ActionCode actionCode, Object param) {

		if (actionCode == ActionCode.ACTION_COMMIT) {
			// Commit current response
			commit(param);
		} else if (actionCode == ActionCode.ACTION_ACK) {
			// Acknowledge request
			sendAck(param);
		} else if (actionCode == ActionCode.ACTION_CLIENT_FLUSH) {
			// Flush
			flush();
		} else if (actionCode == ActionCode.ACTION_CLOSE) {
			// Close
			close();
		} else if (actionCode == ActionCode.ACTION_CUSTOM) {
			// DO NOTHING
		} else if (actionCode == ActionCode.ACTION_REQ_HOST_ADDR_ATTRIBUTE) {
			// Get remote host address
			requestHostAddressAttr();
		} else if (actionCode == ActionCode.ACTION_REQ_LOCAL_NAME_ATTRIBUTE) {
			// Get local host name
			requestLocalNameAttr();
		} else if (actionCode == ActionCode.ACTION_REQ_HOST_ATTRIBUTE) {
			// Get remote host name
			requestHostAttribute();
		} else if (actionCode == ActionCode.ACTION_REQ_LOCAL_ADDR_ATTRIBUTE) {
			// Get local host address
			requestLocalHostAddressAttr();
		} else if (actionCode == ActionCode.ACTION_REQ_REMOTEPORT_ATTRIBUTE) {
			// Get remote port
			requestRemotePortAttr();
		} else if (actionCode == ActionCode.ACTION_REQ_LOCALPORT_ATTRIBUTE) {
			// Get local port
			requestLocalPortAttr();
		} else if (actionCode == ActionCode.ACTION_REQ_SSL_ATTRIBUTE) {
			// request for the SSL attribute
			requestSSLAttr();
		} else if (actionCode == ActionCode.ACTION_REQ_SSL_CERTIFICATE) {
			// Request for the SSL certificate
			requestSSLCertificate();
		} else if (actionCode == ActionCode.ACTION_REQ_SET_BODY_REPLAY) {
			//
			requestSetBodyReplay(param);
		} else if (actionCode == ActionCode.ACTION_AVAILABLE) {
			// make the input buffer available
			makeAvailable();
		} else if (actionCode == ActionCode.ACTION_EVENT_BEGIN) {
			// Begin event
			beginEvent(param);
		} else if (actionCode == ActionCode.ACTION_EVENT_END) {
			// End event
			endEvent(param);
		} else if (actionCode == ActionCode.ACTION_EVENT_SUSPEND) {
			// Suspend event
			suspendEvent();
		} else if (actionCode == ActionCode.ACTION_EVENT_RESUME) {
			// Resume event
			resumeEvent(param);
		} else if (actionCode == ActionCode.ACTION_EVENT_WRITE) {
			// Write event
			writeEvent(param);
		} else if (actionCode == ActionCode.ACTION_EVENT_WRITE) {
			// Timeout event
			timeoutEvent(param);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.coyote.http11.Http11AbstractProcessor#prepareRequest()
	 */
	protected void prepareRequest() {

		http11 = true;
		http09 = false;
		contentDelimitation = false;
		expectation = false;

		if (sslEnabled) {
			request.scheme().setString("https");
		}
		MessageBytes protocolMB = request.protocol();

		if (protocolMB.equals(Constants.HTTP_11)) {
			http11 = true;
			protocolMB.setString(Constants.HTTP_11);
		} else if (protocolMB.equals(Constants.HTTP_10)) {
			http11 = false;
			keepAlive = false;
			protocolMB.setString(Constants.HTTP_10);
		} else if (protocolMB.equals("")) {
			// HTTP/0.9
			http09 = true;
			http11 = false;
			keepAlive = false;
		} else {
			// Unsupported protocol
			http11 = false;
			error = true;
			// Send 505; Unsupported HTTP version
			response.setStatus(505);
		}

		MessageBytes methodMB = request.method();
		if (methodMB.equals(Constants.GET)) {
			methodMB.setString(Constants.GET);
		} else if (methodMB.equals(Constants.POST)) {
			methodMB.setString(Constants.POST);
		}

		MimeHeaders headers = request.getMimeHeaders();

		// Check connection header
		MessageBytes connectionValueMB = headers.getValue("connection");
		if (connectionValueMB != null) {
			ByteChunk connectionValueBC = connectionValueMB.getByteChunk();
			if (findBytes(connectionValueBC, Constants.CLOSE_BYTES) != -1) {
				keepAlive = false;
			} else if (findBytes(connectionValueBC, Constants.KEEPALIVE_BYTES) != -1) {
				keepAlive = true;
			}
		}

		MessageBytes expectMB = null;
		if (http11)
			expectMB = headers.getValue("expect");
		if ((expectMB != null) && (expectMB.indexOfIgnoreCase("100-continue", 0) != -1)) {
			inputBuffer.setSwallowInput(false);
			expectation = true;
		}

		// Check user-agent header
		if ((restrictedUserAgents != null) && ((http11) || (keepAlive))) {
			MessageBytes userAgentValueMB = headers.getValue("user-agent");
			// Check in the restricted list, and adjust the http11
			// and keepAlive flags accordingly
			if (userAgentValueMB != null) {
				String userAgentValue = userAgentValueMB.toString();
				for (int i = 0; i < restrictedUserAgents.length; i++) {
					if (restrictedUserAgents[i].matcher(userAgentValue).matches()) {
						http11 = false;
						keepAlive = false;
						break;
					}
				}
			}
		}

		// Check for a full URI (including protocol://host:port/)
		ByteChunk uriBC = request.requestURI().getByteChunk();
		if (uriBC.startsWithIgnoreCase("http", 0)) {

			int pos = uriBC.indexOf("://", 0, 3, 4);
			int uriBCStart = uriBC.getStart();
			int slashPos = -1;
			if (pos != -1) {
				byte[] uriB = uriBC.getBytes();
				slashPos = uriBC.indexOf('/', pos + 3);
				if (slashPos == -1) {
					slashPos = uriBC.getLength();
					// Set URI as "/"
					request.requestURI().setBytes(uriB, uriBCStart + pos + 1, 1);
				} else {
					request.requestURI().setBytes(uriB, uriBCStart + slashPos,
							uriBC.getLength() - slashPos);
				}
				MessageBytes hostMB = headers.setValue("host");
				hostMB.setBytes(uriB, uriBCStart + pos + 3, slashPos - pos - 3);
			}

		}

		// Input filter setup
		InputFilter[] inputFilters = inputBuffer.getFilters();

		// Parse transfer-encoding header
		MessageBytes transferEncodingValueMB = null;
		if (http11)
			transferEncodingValueMB = headers.getValue("transfer-encoding");
		if (transferEncodingValueMB != null) {
			String transferEncodingValue = transferEncodingValueMB.toString();
			// Parse the comma separated list. "identity" codings are ignored
			int startPos = 0;
			int commaPos = transferEncodingValue.indexOf(',');
			String encodingName = null;
			while (commaPos != -1) {
				encodingName = transferEncodingValue.substring(startPos, commaPos)
						.toLowerCase(Locale.ENGLISH).trim();
				if (!addInputFilter(inputFilters, encodingName)) {
					// Unsupported transfer encoding
					error = true;
					// 501 - Unimplemented
					response.setStatus(501);
				}
				startPos = commaPos + 1;
				commaPos = transferEncodingValue.indexOf(',', startPos);
			}
			encodingName = transferEncodingValue.substring(startPos).toLowerCase(Locale.ENGLISH)
					.trim();
			if (!addInputFilter(inputFilters, encodingName)) {
				// Unsupported transfer encoding
				error = true;
				// 501 - Unimplemented
				response.setStatus(501);
			}
		}

		// Parse content-length header
		long contentLength = request.getContentLengthLong();
		if (contentLength >= 0 && !contentDelimitation) {
			inputBuffer.addActiveFilter(inputFilters[Constants.IDENTITY_FILTER]);
			contentDelimitation = true;
		}

		MessageBytes valueMB = headers.getValue("host");

		// Check host header
		if (http11 && (valueMB == null)) {
			error = true;
			// 400 - Bad request
			response.setStatus(400);
		}

		parseHost(valueMB);

		if (!contentDelimitation) {
			// If there's no content length
			// (broken HTTP/1.0 or HTTP/1.1), assume
			// the client is not broken and didn't send a body
			inputBuffer.addActiveFilter(inputFilters[Constants.VOID_FILTER]);
			contentDelimitation = true;
		}

	}

	/**
	 * Parse host.
	 */
	protected void parseHost(MessageBytes valueMB) {

		if (valueMB == null || valueMB.isNull()) {
			// HTTP/1.0
			// Default is what the socket tells us. Overriden if a host is
			// found/parsed
			request.setServerPort(endpoint.getPort());
			return;
		}

		ByteChunk valueBC = valueMB.getByteChunk();
		byte[] valueB = valueBC.getBytes();
		int valueL = valueBC.getLength();
		int valueS = valueBC.getStart();
		int colonPos = -1;
		if (hostNameC.length < valueL) {
			hostNameC = new char[valueL];
		}

		boolean ipv6 = (valueB[valueS] == '[');
		boolean bracketClosed = false;
		for (int i = 0; i < valueL; i++) {
			char b = (char) valueB[i + valueS];
			hostNameC[i] = b;
			if (b == ']') {
				bracketClosed = true;
			} else if (b == ':') {
				if (!ipv6 || bracketClosed) {
					colonPos = i;
					break;
				}
			}
		}

		if (colonPos < 0) {
			if (!sslEnabled) {
				// 80 - Default HTTP port
				request.setServerPort(80);
			} else {
				// 443 - Default HTTPS port
				request.setServerPort(443);
			}
			request.serverName().setChars(hostNameC, 0, valueL);
		} else {

			request.serverName().setChars(hostNameC, 0, colonPos);

			int port = 0;
			int mult = 1;
			for (int i = valueL - 1; i > colonPos; i--) {
				int charValue = HexUtils.DEC[valueB[i + valueS]];
				if (charValue == -1) {
					// Invalid character
					error = true;
					// 400 - Bad request
					response.setStatus(400);
					break;
				}
				port = port + (charValue * mult);
				mult = 10 * mult;
			}
			request.setServerPort(port);

		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.coyote.http11.Http11AbstractProcessor#prepareResponse()
	 */
	protected void prepareResponse() {

		int statusCode = response.getStatus();
		MimeHeaders headers = response.getMimeHeaders();
		// NOPE
		keepAlive = keepAlive && !statusDropsConnection(statusCode);
		if (!keepAlive) {
			headers.addValue(Constants.CONNECTION).setString(Constants.CLOSE);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.coyote.http11.Http11AbstractProcessor#initializeFilters()
	 */
	protected void initializeFilters() {

		// Create and add the identity filters.
		inputBuffer.addFilter(new IdentityInputFilter());
		outputBuffer.addFilter(new IdentityOutputFilter());

		// Create and add the chunked filters.
		inputBuffer.addFilter(new ChunkedInputFilter());
		outputBuffer.addFilter(new ChunkedOutputFilter());

		// Create and add the void filters.
		inputBuffer.addFilter(new VoidInputFilter());
		outputBuffer.addFilter(new VoidOutputFilter());

		// Create and add the chunked filters.
		// inputBuffer.addFilter(new GzipInputFilter());
		outputBuffer.addFilter(new GzipOutputFilter());

	}

	/**
	 * Add an input filter to the current request.
	 * 
	 * @return false if the encoding was not found (which would mean it is
	 *         unsupported)
	 */
	protected boolean addInputFilter(InputFilter[] inputFilters, String encodingName) {
		if (encodingName.equals("identity")) {
			// Skip
		} else if (encodingName.equals("chunked")) {
			inputBuffer.addActiveFilter(inputFilters[Constants.CHUNKED_FILTER]);
			contentDelimitation = true;
		} else {
			for (int i = 2; i < inputFilters.length; i++) {
				if (inputFilters[i].getEncodingName().toString().equals(encodingName)) {
					inputBuffer.addActiveFilter(inputFilters[i]);
					return true;
				}
			}
			return false;
		}
		return true;
	}

}
