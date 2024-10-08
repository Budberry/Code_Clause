package server;
 
import communication.handshake.*;
import communication.session.IV;
import communication.session.SessionKey;
import meta.Arguments;
import meta.Common;
import meta.Logger;
import communication.threads.ForwardServerClientThread;

import java.lang.Integer;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.io.IOException;
import java.security.cert.X509Certificate;

public class ForwardServer
{
    private static final boolean ENABLE_LOGGING = true;
    public static final int DEFAULTSERVERPORT = 2206;
    public static final String DEFAULTSERVERHOST = "localhost";
    public static final String PROGRAMNAME = "server.ForwardServer";
    private static Arguments arguments;

    private ServerSocket handshakeSocket;

    private ServerSocket listenSocket;
    private String targetHost;
    private int targetPort;

    
    public static void main(String[] args) throws Exception {
        arguments = new Arguments();
        arguments.setDefault("handshakeport", Integer.toString(DEFAULTSERVERPORT));
        arguments.setDefault("handshakehost", DEFAULTSERVERHOST);
        arguments.loadArguments(args);

        ForwardServer srv = new ForwardServer();
        try {
            srv.startForwardServer();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    
    public void startForwardServer() throws Exception {
        // Bind server on given TCP port
        int port = Integer.parseInt(arguments.get("handshakeport"));
        String address = arguments.get("handshakehost");

        try {
            handshakeSocket = new ServerSocket(port);
        } catch (IOException ioe) {
           throw new IOException("Unable to bind to port " + port);
        }

        log("Forward Server started at address " + address + " on TCP port " + port);
        log("Waiting for connections...");
 
        // Accept client connections and process them until stopped
        while(true) {
            ForwardServerClientThread forwardThread;

            try {
               doHandshake();
               setUpSession();
                
               forwardThread = new ForwardServerClientThread(this.listenSocket, this.targetHost, this.targetPort);
               forwardThread.start();
           } catch (IOException e) {
               throw e;
           }
        }
    }

    
    private void doHandshake() throws UnknownHostException, IOException, Exception {
        Socket clientSocket = handshakeSocket.accept();
        String clientHostPort = clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();
        Logger.log("Incoming handshake connection from " + clientHostPort);

        //Step 2, get ClientHello and verify

        HandshakeMessage clientHello = new HandshakeMessage();
        clientHello.recv(clientSocket);

        if (!clientHello.getParameter("MessageType").equals("ClientHello")) {
            System.err.println("Received invalid handshake type!");
            clientSocket.close();
            throw new Error();
        }

        //get client certificate from message
        String clientCertString = clientHello.getParameter("Certificate");
        X509Certificate clientCert = aCertificate.stringToCert(clientCertString);

        //verify certificate is signed by our CA
        HandleCertificate handleCertificate = new HandleCertificate(arguments.get("cacert"));

        if (!handleCertificate.verify(clientCert)) {
            System.err.println("CLIENT CA FAILED VERIFICATION");
            clientSocket.close();
            throw new Error();
        } else {
            //Logger.log("Successful verification of client certificate...");
        }

        // Client is verified, proceed to sending ServerHello
        HandshakeMessage serverHello = new HandshakeMessage();
        serverHello.putParameter(Common.MESSAGE_TYPE, Common.SERVER_HELLO);
        serverHello.putParameter(Common.CERTIFICATE, aCertificate.encodeCert(aCertificate.pathToCert(arguments.get("usercert"))));
        serverHello.send(clientSocket);

        //step 6 server receives ForwardMessage from client and sets up session
        HandshakeMessage forwardMessage = new HandshakeMessage();
        forwardMessage.recv(clientSocket);

        if (!forwardMessage.getParameter(Common.MESSAGE_TYPE).equals(Common.FORWARD_MSG)) {
            System.err.println("Received invalid message type! Should be forward message");
            clientSocket.close();
            throw new Error();
        }

        //Set client's desired target as handshake target
        Handshake.setTargetHost(forwardMessage.getParameter(Common.TARGET_HOST));
        Handshake.setTargetPort(Integer.parseInt(forwardMessage.getParameter(Common.TARGET_PORT)));

        //step 6.1 generate session key and iv
        SessionKey sessionKey = new SessionKey(Common.KEY_LENGTH);
        IV iv = new IV();

        Handshake.sessionKey = sessionKey;
        Handshake.iv = iv;

        //step 6.2 encrypt secretKey and IV with client's public key
        String encryptedSessionKey = AsymmetricCrypto.encrypt(sessionKey.encodeKey(), clientCert.getPublicKey());
        String encryptedIV = AsymmetricCrypto.encrypt(iv.encodeIV(), clientCert.getPublicKey());

        //step 7 Server sends client session message
        HandshakeMessage sessionMsg = new HandshakeMessage();
        sessionMsg.putParameter(Common.MESSAGE_TYPE, Common.SESSION_MSG);
        sessionMsg.putParameter(Common.SESSION_KEY, encryptedSessionKey);
        sessionMsg.putParameter(Common.SESSION_IV, encryptedIV);

        sessionMsg.send(clientSocket);

        clientSocket.close();
        log("Handshake successful!");

        //end of handshake
    }

    private void setUpSession() throws UnknownHostException, IOException, Exception {
        
        listenSocket = new ServerSocket();
        listenSocket.bind(new InetSocketAddress(Handshake.serverHost, Handshake.serverPort));

        
        targetHost = Handshake.targetHost;
        targetPort = Handshake.targetPort;
    }
 
    
    public void log(String aMessage) {
        if (ENABLE_LOGGING)
           System.out.println(aMessage);
    }
 
    static void usage() {
        String indent = "";
        System.err.println(indent + "Usage: " + PROGRAMNAME + " options");
        System.err.println(indent + "Where options are:");
        indent += "    ";
        System.err.println(indent + "--serverhost=<hostname>");
        System.err.println(indent + "--serverport=<portnumber>");        
        System.err.println(indent + "--usercert=<filename>");
        System.err.println(indent + "--cacert=<filename>");
        System.err.println(indent + "--key=<filename>");                
    }
}