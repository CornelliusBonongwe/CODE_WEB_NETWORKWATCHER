import java.awt.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.*;

public class SystemMonitor {

    private static final String TEAMS_WEBHOOK_URL = "https://default1b868d3e2ca64fdbb95a0e8e7e77a9.f5.environment.api.powerplatform.com:443/powerautomate/automations/direct/workflows/7dbc2c13b2fd49fab1d5f91801d82d97/triggers/manual/paths/invoke?api-version=1&sp=%2Ftriggers%2Fmanual%2Frun&sv=1.0&sig=AniMRRvnZbhdyUq6ZKd2TWesQ0-SfUHxd2Ekqd8mG9M";
    
    private static JTextArea logArea;

    public static void main(String[] args) {
        setupGUI();

        Thread monitorThread = new Thread(() -> {
            // Target arrays
            String[] targets = { "8.8.8.8", "192.168.0.190", "192.168.0.149", "192.168.0.9", "192.168.0.5" };
            String[] targetNames = { "Google Gateway", "Premia", "Fundworks", "Sophos Firewall", "Sage/Gateway/Domain Controller" };
            
            boolean[] isNodeDown = new boolean[targets.length];      
            long[] downTimeStarts = new long[targets.length];        
            
            int timeoutInMilliseconds = 3000;
            int loopDelayInSeconds = 120; // 2-minute cycle

            logMessage("=== Starting VLA LAN Network Monitor ===");

            while (true) {
                logMessage("\n--- Running Node Health Checks ---");

                for (int i = 0; i < targets.length; i++) {
                    String ip = targets[i];
                    String name = targetNames[i];

                    try {
                        long startTime = System.currentTimeMillis();
                        boolean isReachable = InetAddress.getByName(ip).isReachable(timeoutInMilliseconds);
                        long endTime = System.currentTimeMillis();
                        long responseTime = endTime - startTime;

                        // Create formatter for exact human-readable timestamps (e.g., 14:02:35)
                        SimpleDateFormat timeFormatter = new SimpleDateFormat("HH:mm:ss");
                        String currentTimeString = timeFormatter.format(new Date());

                        if (isReachable) {
                            // CASE: NODE IS UP. Check if it was previously recorded as down
                            if (isNodeDown[i]) {
                                long totalDowntimeMs = System.currentTimeMillis() - downTimeStarts[i];
                                long totalMinutes = totalDowntimeMs / (1000 * 60);
                                long totalSeconds = (totalDowntimeMs / 1000) % 60;

                                String recoveryMessage = "✅ **NODE RECOVERY**: The node **" + name + "** (" + ip + ") is **BACK ONLINE**!"
                                        + "\n⏱️ Total Downtime: **" + totalMinutes + " minutes, " + totalSeconds + " seconds**."
                                        + "\n⏰ Recovery Verified At: **" + currentTimeString + "**";
                                
                                logMessage("[RECOVERY] " + name + " has recovered. Sending alert.");
                                sendTeamsAlert(recoveryMessage, "00FF00"); // Green border card
                                
                                // Reset memory state for this node
                                isNodeDown[i] = false;
                                downTimeStarts[i] = 0;
                            } else {
                                logMessage("[ONLINE] " + name + " (" + ip + ") responded in " + responseTime + "ms");
                            }

                        } else {
                            // CASE: NODE IS DOWN
                            if (!isNodeDown[i]) {
                                // First time dropping! Mark it down and capture the exact millisecond timestamp
                                isNodeDown[i] = true;
                                downTimeStarts[i] = System.currentTimeMillis();
                                
                                logMessage("[CRITICAL] " + name + " (" + ip + ") went DOWN! Escalating alert...");
                                
                                String alertMessage;
                                if (ip.equals("192.168.0.9")) {
                                    alertMessage = " **CRITICAL INFRASTRUCTURE ALERT**: The **Sophos Firewall** (" + ip + ") is DOWN. The **VLA Network is completely isolated!**"
                                            + "\n⏰ Outage Detected At: **" + currentTimeString + "**";
                                } else {
                                    alertMessage = "The node **" + name + "** (" + ip + ") is UNREACHABLE or experiencing critical network downtime!"
                                            + "\n⏰ Outage Detected At: **" + currentTimeString + "**";
                                }
                                sendTeamsAlert(alertMessage, "FF0000"); // Red border card
                            } else {
                                // Already recorded as down. Log to local terminal window, but DO NOT spam Teams.
                                long currentDowntimeMs = System.currentTimeMillis() - downTimeStarts[i];
                                long currentMinutes = currentDowntimeMs / (1000 * 60);
                                logMessage("[STILL DOWN] " + name + " (" + ip + ") offline for " + currentMinutes + " mins. (Teams notification suppressed)");
                            }
                        }

                    } catch (IOException e) {
                        logMessage("[ERROR] Connection issue testing " + name + ": " + e.getMessage());
                    }
                }

                logMessage("------------------------------------");
                goToSleep(loopDelayInSeconds);
            }
        });

        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private static void goToSleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            logMessage("Monitor execution thread interrupted.");
        }
    }

    private static void setupGUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        JFrame frame = new JFrame("VLA LAN Network Monitor");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(550, 400);
        frame.setLocationRelativeTo(null);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(Color.BLACK);
        logArea.setForeground(Color.GREEN);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        
        JScrollPane scrollPane = new JScrollPane(logArea);
        frame.add(scrollPane, BorderLayout.CENTER);
        frame.setVisible(true);
    }

    private static void logMessage(String message) {
        System.out.println(message);
        SwingUtilities.invokeLater(() -> {
            if (logArea != null) {
                logArea.append(message + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }

    private static void sendTeamsAlert(String messageText, String hexColor) {
        String jsonPayload = "{"
                + "\"@type\": \"MessageCard\","
                + "\"@context\": \"http://schema.org/extensions\","
                + "\"themeColor\": \" " + hexColor + "\","
                + "\"title\": \" VLA NETWORK STATUS UPDATE\","
                + "\"text\": \"" + messageText + "\""
                + "}";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TEAMS_WEBHOOK_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }
}