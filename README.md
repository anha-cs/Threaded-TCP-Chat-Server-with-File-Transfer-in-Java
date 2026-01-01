# Threaded TCP Chat Server with File Transfer in Java

## Project Description

This project implements a **multi-threaded TCP Chat System** consisting of a centralized server and a user-facing client. The system facilitates real-time text communication between multiple concurrent users and includes an integrated **direct peer-to-peer file transfer** mechanism.

The architecture simulates modern messaging applications by separating the command/chat stream from the data transfer stream. While the server coordinates the handshake for file sharing, the actual file data is transmitted over a separate TCP connection established directly between clients to ensure the main chat remains responsive.

---

## 🚀 Features

* **Multi-threaded Architecture:** Handles multiple concurrent client connections using Java's `Thread` class and `Runnable` interface.
* **Real-time Broadcasting:** Messages sent by one user are instantly relayed to all other connected participants.
* **User Tracking:** Automatically notifies the chat group when users join or leave.
* **Peer-to-Peer File Transfer:** Supports sending files directly between clients via a separate dedicated TCP socket.
* **Protocol Commands:** Includes built-in commands for user discovery, file negotiation, and graceful exit.
* **Concurrency Control:** Utilizes thread-safe collections and synchronization to manage the active client list.

---

## 🛠 Architecture & Threading Model

The project relies on a robust multi-threaded model to prevent blocking I/O operations from freezing the application.

### Server-Side Threads

* **Main Listener Thread:** Listens for incoming connections on a `ServerSocket`.
* **Client Handler Threads:** One per connected client. Manages message relaying and command parsing.

### Client-Side Threads

* **Message Listener Thread:** Continuously listens for incoming data from the server and prints it to the console.
* **User Input Thread:** Captures console input from the user to send messages/commands.
* **File Transfer/Reception Threads:** Temporary threads spawned specifically for a single file transmission to keep the chat active during large transfers.

---

## ⌨️ Supported Commands

| Command | Action |
| --- | --- |
| `/who` | Lists all currently online users. |
| `/sendfile <user> <filename>` | Requests to send a file to a specific user. |
| `/acceptfile <user>` | Approves an incoming file transfer request. |
| `/rejectfile <user>` | Declines an incoming file transfer request. |
| `/quit` | Gracefully disconnects from the server and closes the application. |

---

## 📦 Installation & Execution

### Prerequisites

* Java Development Kit (JDK) 8 or higher.

### Compilation

Compile both the server and the client using the standard Java compiler:

```bash
javac tcpcss.java
javac tcpccs.java

```

### Running the Server

The server listens on a default port (e.g., 12345) unless specified otherwise.

```bash
java tcpcss.java [optional_port]

```

### Running the Client

Connect to the server by providing the hostname and your desired username.

```bash
java tcpccs.java <server_hostname> <username>

```

---

## 📂 File Transfer Protocol Flow

1. **Request:** Sender issues `/sendfile`. The server notifies the Receiver.
2. **Approval:** Receiver issues `/acceptfile`. The server notifies the Sender.
3. **Setup:** Sender opens a new `ServerSocket` on a dynamic port and sends this port number to the Receiver via the Server.
4. **Transfer:** Receiver connects to the Sender’s dynamic port. Both clients spawn dedicated threads to stream the file data.
5. **Cleanup:** Once the file is written to disk, the temporary sockets and threads are closed.

---

## ⚠️ Important Notes

* **File Location:** For simplicity, files are expected to be in the application's root directory.
* **Local Testing:** If running multiple clients on the same machine, run them from different folders to avoid file naming conflicts during transfers.
* **Graceful Exit:** Always use `/quit` to ensure the server can clean up your connection and notify other users of your departure.

## 🎓 Learning Goals Achieved

* Mastered Java's `Socket` and `ServerSocket` APIs.
* Implemented thread-safe resource management using `ConcurrentHashMap` and `synchronized` blocks.
* Designed a custom application-layer protocol for chat and file negotiation.
* Separated control logic (Chat) from data logic (File Transfer).

---
