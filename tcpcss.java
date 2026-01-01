import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatServer {
    private static final AtomicInteger counter = new AtomicInteger(0);
    private static final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws IOException {
        int port = 12345; // default port
        if (args.length == 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Invalid port number. Using default port 12345.");
            }
        } else if (args.length > 1) {
            System.out.println("Usage: java tcpcss.java [port]");
            return;
        }

        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Server listening on port " + port);
        System.out.println("Waiting for connections...");

        while (true) {
            Socket clientSocket = serverSocket.accept();
            ClientHandler handler = new ClientHandler(clientSocket);
            clients.add(handler);
            new Thread(handler).start();
        }
    }

    public static void broadcast(String message, ClientHandler sender) {
        synchronized (clients) {
            for (ClientHandler client : clients) {
                // broadcast to all clients, including sender if sender is null (for server messages)
                if (sender == null || client != sender) {
                    client.sendMessage(message);
                }
            }
        }
    }

    // Thread to handle each client's connection
    static class ClientHandler implements Runnable {
        private Socket clientSocket;
        private PrintWriter out;
        private BufferedReader in;
        private String username;

        ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        public void sendMessage(String message) {
            if (out != null) {
                try {
                    out.println(message);
                    out.flush();
                } catch (Exception e) {
                    System.out.println("Fail to send message to " + username + ": " + e.getMessage());
                }
            }
        }

        @Override
        public void run() {
            System.out.println("New connection, thread name is " + Thread.currentThread().getName() +
                    ", IP is: " + clientSocket.getInetAddress().getHostAddress() +
                    ", port: " + clientSocket.getPort());

            int requestNum = counter.incrementAndGet();
            System.out.println("Adding to list of sockets as " + requestNum);

            try {
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                out = new PrintWriter(clientSocket.getOutputStream(), true);

                username = in.readLine();
                System.out.println("[" + username + "] has joined the chat.");
                broadcast("[" + username + "] has joined the chat.", this);

                String message;
                while ((message = in.readLine()) != null) {
                    if (message.equalsIgnoreCase("/quit")) {
                        break;
                    } else if (message.equalsIgnoreCase("/who")) {
                        System.out.println("[" + username + "] requested online users list.");
                        String userList = ChatServer.getUserList();
                        System.out.println(userList);
                        out.println(userList);
                    } else if (message.startsWith("/sendfile")) {
                        handleSendFile(message);
                    } else if (message.startsWith("/acceptfile") || message.startsWith("/rejectfile")) {
                        handleFileResponse(message);
                    } else if (message.startsWith("/fileport")) {
                        // Format: /fileport <recipient> <port> (Sent by sender's client)
                        String[] parts = message.split(" ", 3);
                        if (parts.length < 3) continue;

                        String recipientName = parts[1];
                        // Get the full message to forward

                        ClientHandler recipient = findClientByName(recipientName);
                        if (recipient != null) {
                        // Send silently to the recipient only
                        recipient.sendMessage(message);
                        } else {
                            out.println("[Server] User '" + recipientName + "' not found to relay file port.");
                        }
                    } else if (message.startsWith("/filecomplete")) {
                        String fileMsg = message.substring("/filecomplete ".length());
                        System.out.println(fileMsg); // print on server console
                        broadcast(fileMsg, null);    // broadcast to all clients
                    } else {
                        System.out.println("[" + username + "] " + message);
                        broadcast("[" + username + "] " + message, this);
                    }
                }
            } catch (IOException e) {
                // Log critical errors (unlike connection reset) but let finally block handle cleanup
                if (!e.getMessage().contains("Connection reset")) {
                     System.out.println("Error for " + username + ": " + e.getMessage());
                }
            } finally {
                System.out.println("[" + username + "] has left the chat.");
                clients.remove(this);
                broadcast("[" + username + "] has left the chat.", this);
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.out.println("Error closing socket: " + e.getMessage());
                }
            }
        }

        private void handleSendFile(String message) {
            String[] parts = message.split(" ", 4);
            if (parts.length < 3) {
                out.println("Usage: /sendfile <recipient> <filename>");
                return;
            }

            String recipientName = parts[1];
            String fileName = parts[2];
            String sizeKB = (parts.length >= 4) ? parts[3] : "unknown size";

            ClientHandler receiver = findClientByName(recipientName);
            if (receiver == null) {
                out.println("[Server] User '" + recipientName + "' not found.");
                return;
            }

            // 1. Server broadcasts the initiation to ALL clients
            String publicMsg = "[File transfer initiated from " + username + " to " + recipientName
                    + " " + fileName + " (" + sizeKB + ")]";
            System.out.println(publicMsg); // server log
            broadcast(publicMsg, null);     // broadcast to all

            // 2. Server sends a PRIVATE command to the RECIPIENT to prompt acceptance
            // Format: /filerequest <sender> <filename> <size>
            String privateMsg = "/filerequest " + username + " " + fileName + " " + sizeKB;
            receiver.sendMessage(privateMsg);
        }

        private void handleFileResponse(String message) {
            String[] parts = message.split(" ", 3);
            if (parts.length < 2) {
                // Log the incomplete command and send a notification back to the client
                out.println("[Server] Command incomplete. Usage: /acceptfile <sendername> or /rejectfile <sendername>");
                return; 
    }

            String command = parts[0];
            String senderName = parts[1];
            String fileName = (parts.length == 3) ? parts[2] : "unknown_file"; 

            ClientHandler sender = findClientByName(senderName);
            if (sender == null) {
                out.println("[Server] User '" + senderName + "' not found.");
                return;
            }

            if (command.startsWith("/acceptfile")) {
                System.out.println("[File transfer accepted from " + senderName + " to " + username + "]");
                broadcast("[File transfer accepted from " + senderName + " to " + username + "]", null);
    
                // Send PRIVATE command to the SENDER's client to trigger the file transfer thread
                // Format: /fileaccepted <recipient> <filename>
                sender.sendMessage("/fileaccepted " + username + " " + fileName);
                
            } else if (command.startsWith("/rejectfile")) {
                System.out.println("[" + username + " rejected file transfer from " + senderName + "]");
                // Send rejection privately to the SENDER
                sender.sendMessage("[File transfer rejected by " + username + "]");
                // Optionally broadcast a rejection notice to all
                broadcast("[File transfer rejected by " + username + " for a file from " + senderName + "]", null);
            }
        }

        private ClientHandler findClientByName(String name) {
            synchronized (clients) {
                for (ClientHandler c : clients) {
                    if (c.username != null && c.username.equalsIgnoreCase(name)) {
                        return c;
                    }
                }
            }
            return null;
        }
    }

    public static String getUserList() {
        StringBuilder sb = new StringBuilder();
        sb.append("[Online users: ");
        synchronized (clients) {
            boolean first = true;
            for (ClientHandler client : clients) {
                if (client.username != null) {
                    if (!first) sb.append(", ");
                    sb.append(client.username);
                    first = false;
                }
            }
        }
        sb.append("]");
        return sb.toString();
    }
}