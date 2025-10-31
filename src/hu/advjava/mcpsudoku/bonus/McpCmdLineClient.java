package hu.advjava.mcpsudoku.bonus;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class McpCmdLineClient {

    private static final String RESPONSE_URL = "https://api.openai.com/v1/responses";
    private static final String MODEL_NAME = "gpt-4.1";

    record McpConfig(String url, String apiKey) {}

    public static void main(String[] args) throws Exception {
		var cfg = loadConfig();

        HttpResponse<String> resp = sendRequest(cfg);
        System.out.println("Status: " + resp.statusCode());
        System.out.println(resp.body());
    }

	private static McpConfig loadConfig() {
		String mcpUrl = System.getenv("MCP_URL");
        if (mcpUrl == null || mcpUrl.isBlank()) {
            throw new IllegalStateException("Set MCP_URL as an environment variable.");
        }
        String apiKey = System.getenv("API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Set API_KEY as an environment variable. TODO how to get API key.");
        }

        return new McpConfig(mcpUrl, apiKey);
	}

	private static HttpResponse<String> sendRequest(McpConfig cfg) throws IOException, InterruptedException {
		// Build a Responses API request that mounts your MCP server as a tool
        String body = requestBody(cfg.url());

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(RESPONSE_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return http.send(req, HttpResponse.BodyHandlers.ofString());
	}

	private static String requestBody(String mcpUrl) {
		return """
        {
          "model": "%s",
          "input": "Using the attached SudokuMCP tool, generate a 'hard' puzzle, then call solve_sudoku on it. Return both the generated and solved boards as compact 9x9 arrays.",
          "tools": [
            {
              "type": "mcp",
              "server_label": "sudoku",
              "server_url": "%s",
              "allowed_tools": ["generate_sudoku", "solve_sudoku"],
              "require_approval": "never"
            }
          ],
          "temperature": 0.2,
          "max_output_tokens": 1200
        }
        """.formatted(MODEL_NAME, mcpUrl);
	}
}
