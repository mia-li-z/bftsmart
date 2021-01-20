package bftsmart.communication.server.socket;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bftsmart.communication.queue.MessageQueue;
import bftsmart.communication.server.AbstractServersCommunicationLayer;
import bftsmart.communication.server.MessageConnection;
import bftsmart.communication.server.SocketUtils;
import bftsmart.reconfiguration.ReplicaTopology;
import bftsmart.reconfiguration.ViewTopology;
import utils.io.RuntimeIOException;

/**
 * @author huanghaiquan
 *
 */
public class SocketServerCommunicationLayer extends AbstractServersCommunicationLayer {
	private static final Logger LOGGER = LoggerFactory.getLogger(SocketServerCommunicationLayer.class);

	private Map<Integer, IncomingSockectConnection> incomingConnections = new ConcurrentHashMap<Integer, IncomingSockectConnection>();

	private ServerSocket serverSocket;

	private final Object acceptingLock = new Object();

	public SocketServerCommunicationLayer(String realmName, ReplicaTopology topology, MessageQueue messageInQueue)
			throws Exception {
		super(realmName, topology, messageInQueue);
	}

	private ServerSocket initServerSocket(ViewTopology topology, int port) throws IOException {
		ServerSocket serverSocket = new ServerSocket(port);
		serverSocket.setSoTimeout(10000);
		serverSocket.setReuseAddress(true);

		return serverSocket;
	}

	private void acceptConnection(ServerSocket ssc) {
		while (doWork) {
			try {
				Socket newSocket = ssc.accept();
				SocketUtils.setSocketOptions(newSocket);
				int remoteId = new DataInputStream(newSocket.getInputStream()).readInt();

				doRequest(newSocket, remoteId);
				
				LOGGER.info("I am {} establishConnection run!", this.topology.getStaticConf().getProcessId());
			} catch (SocketTimeoutException ex) {
				// timeout on the accept... do nothing
			} catch (Exception ex) {
				if (doWork) {
					LOGGER.error("Unexpected error occurred while accepting incoming connection! --[CurrentProcessId="
							+ this.topology.getStaticConf().getProcessId() + "]" + ex.getMessage(), ex);
					try {
						Thread.sleep(2000);
					} catch (InterruptedException e1) {
					}
				}
			}
		}

		try {
			ssc.close();
		} catch (Throwable e) {
			// other exception or error
			LOGGER.warn("Error occurred while closing the server socket of current node! --" + e.getMessage(), e);
		}

		LOGGER.info("ServerCommunicationLayer stopped! --[" + this.topology.getStaticConf().getProcessId() + "]");
	}

	private void doRequest(Socket newSocket, int remoteId) throws IOException {
		if (!this.topology.isCurrentViewMember(remoteId)) {
			LOGGER.warn(
					"The incoming socket will be aborted because it is from a remote node beyond the current view! --[RemoteId={}][CurrentId={}]",
					remoteId, me);
			newSocket.close();
			return;
		}
		synchronized (acceptingLock) {
			IncomingSockectConnection conn = this.incomingConnections.get(remoteId);
			if (conn == null) { 
				conn = new IncomingSockectConnection(realmName, topology,
						remoteId, messageInQueue);
				this.incomingConnections.put(remoteId, conn);
			} else {
				// reconnection
				if (conn.isAlived()) {
					// don't interrupt aliving connection;
					LOGGER.warn(
							"Abort the new incoming socket because an aliving connection from the same remote already exist! --[ExpectedConnectionType={}][RemoteId={}][CurrentId={}]",
							conn.getClass().getName(), remoteId, me);
					newSocket.close();
					return;
				}
			}
			conn.accept(newSocket);
		}
	}

	// ******* EDUARDO BEGIN: List entry that stores pending connections,
	// as a server may accept connections only after learning the current view,
	// i.e., after receiving the response to the join*************//
	// This is for avoiding that the server accepts connectsion from everywhere
	public class PendingConnection {

		public Socket s;
		public int remoteId;

		public PendingConnection(Socket s, int remoteId) {
			this.s = s;
			this.remoteId = remoteId;
		}
	}

	@Override
	protected void startCommunicationServer() {
		int port = topology.getStaticConf().getServerToServerPort(me);
		ServerSocket ssc;
		try {
			ssc = initServerSocket(topology, port);
		} catch (IOException e) {
			throw new RuntimeIOException(e.getMessage(), e);
		}

		Thread thrd = new Thread(new Runnable() {
			@Override
			public void run() {
				acceptConnection(ssc);
			}
		}, "SERVER-COMMUNICATION-LAYER[Id=" + me + "][Port=" + port + "]");

		this.serverSocket = ssc;

		thrd.setDaemon(true);
		thrd.start();
	}

	@Override
	protected void closeCommunicationServer() {
		ServerSocket ssc = serverSocket;
		serverSocket = null;
		if (ssc != null) {
			try {
				ssc.close();
			} catch (Exception e) {
				LOGGER.warn(String.format("Error occurred while closing server socket! --%s --[CurrentId=%s]",
						e.getMessage(), me), e);
			}
		}
	}

	@Override
	protected MessageConnection connectRemote(int remoteId) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected MessageConnection acceptRemote(int remoteId) {
		IncomingSockectConnection conn = incomingConnections.get(remoteId);
		if (conn == null) {
			synchronized (acceptingLock) {
				conn = incomingConnections.get(remoteId);
				if (conn == null) {
					conn = new IncomingSockectConnection(realmName, topology, remoteId, messageInQueue);
					incomingConnections.put(remoteId, conn);
				}
			}
		}
		return conn;
	}

	// ******* EDUARDO END **************//
}
