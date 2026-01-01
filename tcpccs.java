import java.io.*;
import java.net.*;

class ChatClient {
    private static String username;
    private static PrintWriter out;
    
    // Static variables to hold the details of the incoming file request
    private static String pendingSender;
    private static String pendingFileName;
    private static String pendingFileSize;
    
    private static String serverHost; 

    public static void main(String args[]) throws IOException {
        int port = 12345;  // default port
        if (args.length < 2) {
            System.out.println("Usage: java tcpccs.java <hostname> <username> [port]");
            return;
        } else if (args.length == 3) {
            try {
                port = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.out.println("Invalid port number. Using default port 12345.");
            }
        }

        serverHost = args[0]; 
        username = args[1];
        InetSocketAddress serverAddress = new InetSocketAddress(serverHost, port);
        Socket socket = new Socket();
        socket.connect(serverAddress);

        System.out.println("Connected to server. You can start sending messages.");

        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter outWriter = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader userInput = new BufferedReader(new InputStreamReader(System.in))
        ) {
            out = outWriter; // assign to static variable

            // Thread to continuously read messages from server
            Thread readerThread = new Thread(() -> {
                try {
                    String msg;
                    while ((msg = in.readLine()) != null) {
                        if (msg.startsWith("/fileport ")) {
                            // Sent by SENDER via server: /fileport <recipient> <port>
                            // This initiates the RECEIVER thread
                            String[] parts = msg.split(" ", 3);
                            if (parts.length == 3) {
                                String sender = parts[1]; // The current client's username (redundant)
                                int portNum = Integer.parseInt(parts[2]);
                                
                                // Start file receiver in a separate thread. We use serverHost to connect to the sender.
                                new Thread(() -> startFileReceiver(pendingSender, portNum)).start(); 
                            }
                        } else if (msg.startsWith("/filerequest ")) {
                            // Sent privately by Server to RECIPIENT: /filerequest <sender> <filename> <size>
                            String[] parts = msg.split(" ", 4);
                            if (parts.length >= 4) {
                                pendingSender = parts[1];
                                pendingFileName = parts[2];
                                pendingFileSize = parts[3];
                            }
                        } else if (msg.startsWith("/fileaccepted ")) {
                            // Sent privately by Server to SENDER: /fileaccepted <recipient> <filename>
                            String[] parts = msg.split(" ", 3);
                            if (parts.length >= 3) {
                                String recipient = parts[1];
                                String filename = parts[2];
                                
                                // This is the SENDER. Start sending the file now.
                                startFileSender(recipient, filename);
                            }
                        } else {
                            // Normal chat messages
                            System.out.println(msg);
                        }
                    }
                } catch (IOException e) {}
            });
            readerThread.setDaemon(true);  // allows program to exit
            readerThread.start();

            // Send username as first message
            out.println(username);

            // Main thread reads user input and sends to server
            String message;
            while ((message = userInput.readLine()) != null) {
                if (message.startsWith("/sendfile ")) {
                    handleSendFile(message);
                } else if (message.startsWith("/acceptfile ")) {
                    handleAcceptFile(message);
                } else if (message.startsWith("/rejectfile ")) {
                    handleRejectFile(message);
                } else {
                    out.println(message);
                }
                if (message.equalsIgnoreCase("/quit")) break;
            }

            socket.close();
        } catch (IOException e) {
            System.out.println("Unable to connect to server: " + e.getMessage());
        }
    }

    private static void handleSendFile(String command) {
        String[] parts = command.split(" ", 3);
        if (parts.length < 3) {
            System.out.println("Usage: /sendfile <recipient> <filename>");
            return;
        }

        String recipient = parts[1];
        String filename = parts[2];
        File file = new File(filename);

        if (!file.exists()) {
            System.out.println("File not found: " + filename);
            return;
        }

        long sizeBytes = file.length();
        String sizeKB = (sizeBytes / 1024) + " KB";

        // Send to server: includes size info so server can broadcast & send private request
        out.println("/sendfile " + recipient + " " + filename + " " + sizeKB);
        
        // Store info locally in case the acceptance comes back.
        // This is only for the SENDER's context.
        pendingSender = username; 
        pendingFileName = filename;
        pendingFileSize = sizeKB; 
    }

    private static void handleAcceptFile(String command) {
        String[] parts = command.split(" ", 2);
        if (parts.length < 2) return;
        String senderName = parts[1];

        // Check if the accepted user matches the pending user
        if (!senderName.equalsIgnoreCase(pendingSender) || pendingFileName == null) {
            System.out.println("[Server] No pending file request from " + senderName + ".");
            return;
        }
        
        // Tell server we accepted. Format: /acceptfile <sender> <filename>
        // Server will relay /fileaccepted back to the sender
        out.println("/acceptfile " + senderName + " " + pendingFileName);
    }
    
    private static void handleRejectFile(String command) {
        String[] parts = command.split(" ", 2);
        if (parts.length < 2) return;
        String senderName = parts[1];
        
        // Check if the rejected user matches the pending user
        if (!senderName.equalsIgnoreCase(pendingSender) || pendingFileName == null) {
            System.out.println("[Server] No pending file request from " + senderName + " to reject.");
        return;
    }
        // Send reject to server. Server will relay to sender.
        out.println(command); 
        
        // Clear pending state
        if (senderName.equalsIgnoreCase(pendingSender)) {
            pendingSender = null;
            pendingFileName = null;
            pendingFileSize = null;
        }
    }

    // Sender side: starts after recipient accepts and server sends /fileaccepted
    private static void startFileSender(String recipient, String filename) {
        new Thread(() -> {
            File fileToSend = new File(filename);
            if (!fileToSend.exists()) {
                System.out.println("[File Transfer Error: File " + filename + " not found locally.]");
                return;
            }
            
            try (ServerSocket serverSocket = new ServerSocket(0)) {
                int port = serverSocket.getLocalPort();
                
                // Set a timeout in case the receiver never connects
                serverSocket.setSoTimeout(15000); // 15 seconds

                // Notify receiver (via server) which port to connect to
                // Format: /fileport <recipient> <port>
                out.println("/fileport " + recipient + " " + port);

                Socket receiverSocket = serverSocket.accept();
                try (FileInputStream fis = new FileInputStream(fileToSend);
                     OutputStream os = receiverSocket.getOutputStream()) {

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    long totalBytes = 0;

                    while ((bytesRead = fis.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }

                    os.flush();
                    long sizeKB = totalBytes / 1024;

                } finally {
                    receiverSocket.close();
                }

            } catch (SocketTimeoutException e) {
                 System.out.println("[File transfer failed: Recipient " + recipient + " timed out during connection.]");
            } catch (IOException e) {
                System.out.println("[File transfer failed: connection lost with " + recipient + "]");
            }
        }).start();
    }

    // Receiver side: starts when readerThread receives /fileport
    private static void startFileReceiver(String sender, int port) {
        // Use the filename provided in the pending request for the save name
        String saveFileName = "received_" + sender + "_" + pendingFileName;
        File receivedFile = new File(saveFileName);
        
        try (Socket socket = new Socket(serverHost, port); // Connect to the SENDER's IP and port
             InputStream in = socket.getInputStream();
             FileOutputStream fos = new FileOutputStream(receivedFile)) {
 
            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalBytes = 0;

            while ((bytesRead = in.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            long sizeKB = totalBytes / 1024;
            String completeMsg = "[File transfer complete from " + sender + " to "
                    + username + " " + pendingFileName + " (" + sizeKB + " KB)]";

            // Send message to server to broadcast to everyone
            out.println("/filecomplete " + completeMsg);
        
        } catch (ConnectException e) {
            System.out.println("[File transfer failed: Could not connect to sender's port. Ensure IP/Port is reachable.]");
            if (receivedFile.exists()) receivedFile.delete(); // remove partial file
        } catch (IOException e) {
            System.out.println("[File transfer failed: " + e.getMessage() + "]");
            if (receivedFile.exists()) {
                receivedFile.delete(); // remove partial file
            }
        } finally {
            // Clear pending state after transfer attempt
            pendingSender = null;
            pendingFileName = null;
            pendingFileSize = null;
        }
    }
}