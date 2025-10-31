package hu.advjava.mcpsudoku.bonus;

import java.awt.BorderLayout;
import java.net.URI;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

//org.glassfish.jersey.media:jersey-media-sse:3.1.11
//org.glassfish.jersey.media:jersey-media-json-processing:3.1.11
//jakarta.activation:jakarta.activation-api:2.1.2
//jakarta.json:jakarta.json-api:2.1.3
//org.glassfish.jersey.core:jersey-common:3.1.11
//org.glassfish.jersey.inject:jersey-hk2:3.1.11
//org.glassfish.jersey.containers:jersey-container-grizzly2-http:3.1.11

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;

public class McpServerWithGuiClient {
    public static void main(String[] args) throws Exception {
        String baseUri = "http://127.0.0.1:8080";

        HttpServer server = runServer(baseUri);
        runGui(baseUri);

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdownNow));
    }

	private static void runGui(String baseUri) {
		SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Sudoku MCP");
            JTabbedPane tabs = new JTabbedPane();
            JButton addClient = new JButton("Add Client");

            addClient.addActionListener(_ -> {
            	SudokuPanel[] panelRef = new SudokuPanel[1];
                panelRef[0] = new SudokuPanel(baseUri, () -> {
                    int idx = tabs.indexOfComponent(panelRef[0]);
                    if (idx >= 0) tabs.removeTabAt(idx);
                });
                tabs.addTab("Client " + (tabs.getTabCount() + 1), panelRef[0]);
            });

            frame.setLayout(new BorderLayout());
            frame.add(addClient, BorderLayout.NORTH);
            frame.add(tabs, BorderLayout.CENTER);
            frame.setSize(600, 600);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setVisible(true);
        });
	}

	private static HttpServer runServer(String baseUri) {
		ResourceConfig rc = new ResourceConfig()
	        .packages("com.advjava.mcp")
	        .register(McpResource.class) // REGISTER EXPLICITLY — don’t depend on package scanning
	        .register(org.glassfish.jersey.media.sse.SseFeature.class)
	        .register(org.glassfish.jersey.jsonp.JsonProcessingFeature.class)
	        .property(ServerProperties.WADL_FEATURE_DISABLE, true);  // <- turn off WADL
        HttpServer server = GrizzlyHttpServerFactory.createHttpServer(URI.create(baseUri), rc);
        return server;
	}
}
