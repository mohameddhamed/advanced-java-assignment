package hu.advjava.mcpsudoku.bonus;

import java.util.function.Function;

import hu.advjava.mcpsudoku.ExampleSudoku;
import hu.advjava.mcpsudoku.SudokuSolver;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

@Path("/mcp")
public class McpResource {
    public static final Function<String, String> nameToUri = name ->
		"sudoku://examples/%s".formatted(name.toLowerCase().replaceAll("_", "-"));

    @Context
    private Sse sse;

    /* ---- Accept GET (200) so connectors probing don't fail ---- */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getInfo(@Context HttpHeaders headers, @Context UriInfo ui) {
    	logProbe("GET", ui, headers);
    	JsonObject body = Json.createObjectBuilder()
          .add("ok", true)
          .add("endpoint", "/mcp")
          .add("hint", "POST JSON-RPC here; optional SSE at GET /mcp/stream")
          .build();
    	return Response.ok(body).build();
    }
    /* ---- HEAD and OPTIONS should also be 200 ---- */
    @HEAD
    public Response head(@Context HttpHeaders headers, @Context UriInfo ui) {
    	logProbe("HEAD", ui, headers);
    	return Response.ok().build();
    }
    // Preflight / browser convenience
    @OPTIONS
    @Path("{any: .*}")
    public Response options(@Context HttpHeaders headers, @Context UriInfo ui) {
    	logProbe("OPTIONS", ui, headers);
	    return Response.ok()
	        .header("Access-Control-Allow-Origin", "*")
	        .header("Access-Control-Allow-Headers", "Content-Type, Authorization")
	        .header("Access-Control-Allow-Methods", "GET,POST,HEAD,OPTIONS")
	        .build();
    }

    /* -------------------- POST (JSON) — non-SSE -------------------- */

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleJson(JsonObject request, @Context HttpHeaders headers, @Context UriInfo ui) {
    	logProbe("HEAD", ui, headers);
	  try {
	    JsonObject resp = dispatch(request); // builds {"jsonrpc":"2.0", "id":..., "result"| "error":...}
	    return Response.ok(resp, MediaType.APPLICATION_JSON_TYPE)
	                   .header("Cache-Control", "no-cache")
	                   .build();
	  } catch (Throwable t) {
	    t.printStackTrace();
	    JsonObject err = errorEnvelope(request.getInt("id", -1), -32000, "Server error: " + t.getMessage());
	    return Response.ok(err, MediaType.APPLICATION_JSON_TYPE).build();
	  }
    }

    /* -------------------- POST (SSE) — streaming -------------------- */

    // Same path, different negotiated media type
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void handleSse(JsonObject request, @Context SseEventSink sink) {
      try {
        JsonObject envelope = dispatch(request);
        OutboundSseEvent evt = sse.newEventBuilder()
            .name("jsonrpc")
            .mediaType(MediaType.APPLICATION_JSON_TYPE)
            .data(JsonObject.class, envelope)
            .build();
        sink.send(evt);
        // Keep open if you will stream multiple messages; otherwise do nothing and Grizzly will close when method returns.
      } catch (Throwable t) {
        t.printStackTrace();
        JsonObject err = errorEnvelope(request.getInt("id", -1), -32000, "Server error: " + t.getMessage());
        OutboundSseEvent evt = sse.newEventBuilder()
            .name("jsonrpc")
            .mediaType(MediaType.APPLICATION_JSON_TYPE)
            .data(JsonObject.class, err)
            .build();
        sink.send(evt);
      }
    }

    /* -------------------- Optional push channel -------------------- */

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void stream(@Context SseEventSink sink) {
      // Send a quick ready event so proxies see data promptly
      JsonObject ready = Json.createObjectBuilder()
          .add("jsonrpc", "2.0")
          .add("method", "server/ready")
          .add("params", Json.createObjectBuilder())
          .build();

      sink.send(sse.newEventBuilder()
          .name("jsonrpc")
          .mediaType(MediaType.APPLICATION_JSON_TYPE)
          .data(JsonObject.class, ready)
          .build());
    }

    /* -------------------- Core dispatcher -------------------- */
    public JsonObject dispatch(JsonObject request) {
        String method = request.getString("method", "");
        int id = request.getInt("id", -1);

        System.out.println("MCP <- " + method + " (id=" + id + ")");
        try {
	        JsonObject result;
	        switch (method) {
	        /* ----------------------- Lifecycle ----------------------- */
	        case "initialize":
	            result = Json.createObjectBuilder()
	                    .add("protocolVersion", "2025-06-18")
	                    .add("capabilities", Json.createObjectBuilder()
	                            .build())
	                    .add("serverInfo", Json.createObjectBuilder()
	                            .add("name", "SudokuMCP")
	                            .add("version", "1.0").build())
	                    .add("instructions",
	                         "This server exposes sudoku tools (solve_sudoku, generate_sudoku), " +
	                         "a prompt (explain_solution), and a few examples of puzzles as resources.")
	                    .build();
	            return okEnvelope(id, result);
	        /* ----------------------- Tools --------------------------- */
	        case "tools/list":
	            // Advertise tools (names + JSON Schemas)
	            result = Json.createObjectBuilder()
	                    .add("tools", Json.createArrayBuilder()
	                            .add(Json.createObjectBuilder()
	                                    .add("name", "solve_sudoku")
	                                    .add("description", "Solve a 9x9 Sudoku board. Zeros mean blanks.")
	                                    .add("inputSchema", Json.createObjectBuilder()
	                                            .add("type", "object")
	                                            .add("properties", Json.createObjectBuilder()
	                                                    .add("board", Json.createObjectBuilder()
	                                                            .add("type", "array")
	                                                            .add("minItems", 9)
	                                                            .add("maxItems", 9)
	                                                            .add("items", Json.createObjectBuilder()
	                                                                    .add("type", "array")
	                                                                    .add("minItems", 9)
	                                                                    .add("maxItems", 9)
	                                                                    .add("items", Json.createObjectBuilder()
	                                                                            .add("type", "integer")
	                                                                            .add("minimum", 0)
	                                                                            .add("maximum", 9)))))
	                                            .add("required", Json.createArrayBuilder().add("board"))))
	                            .add(Json.createObjectBuilder()
	                                    .add("name", "generate_sudoku")
	                                    .add("description", "Generate a new Sudoku puzzle.")
	                                    .add("inputSchema", Json.createObjectBuilder()
	                                            .add("type", "object")
	                                            .add("properties", Json.createObjectBuilder()
	                                                    .add("difficulty", Json.createObjectBuilder()
	                                                            .add("type", "string")
	                                                            .add("enum", Json.createArrayBuilder()
	                                                                    .add("easy").add("medium").add("hard").add("expert").add("master").add("extreme"))
	                                                            .add("default", "easy")))
	                                            .add("required", Json.createArrayBuilder().add("difficulty")))))
	                    .build();
	            return okEnvelope(id, result);

	        case "tools/call": {
	            final JsonObject params = request.getJsonObject("params");
	            final String toolName = params.getString("name", "");
	            final JsonObject args = params.getJsonObject("arguments");

	            if ("solve_sudoku".equals(toolName)) {
	                final JsonArray arr = args.getJsonArray("board");
	                int[][] board = jsonToBoard(arr);
	                int[][] work = SudokuSolver.deepCopy(board);
	                SudokuSolver.State state = SudokuSolver.solve(work, false);
	                if (state == SudokuSolver.State.SOLVED) {
	                    state = SudokuSolver.solveCount(board); // uniqueness check (your code)
	                }
	                JsonObject res = Json.createObjectBuilder()
	                        .add("board", boardToJson(work))
	                        .add("state", state.toString())
	                        .build();
	                result = Json.createObjectBuilder().add("content",
	                				Json.createArrayBuilder().add(
	                						Json.createObjectBuilder().add("type", "text").add("text", res.toString()).build()).build()).build();
	                return okEnvelope(id, result);
	            }

	            if ("generate_sudoku".equals(toolName)) {
	                final String difficulty = args.getString("difficulty", "easy");
	                int[][] puzzle = SudokuSolver.generate(difficulty);
	                JsonObject res = Json.createObjectBuilder()
	                        .add("board", boardToJson(puzzle))
	                        .add("difficulty", difficulty)
	                        .build();
	                result = Json.createObjectBuilder().add("content",
                					Json.createArrayBuilder().add(
                							Json.createObjectBuilder().add("type", "text").add("text", res.toString()).build()).build()).build();
	                return okEnvelope(id, result);
	            }

	            return errorEnvelope(id, -32601, "Unknown tool: " + toolName);
	        }

	        /* ----------------------- Prompts ------------------------- */
	        case "prompts/list":
	            // A single prompt called "explain_solution"
	            result = Json.createObjectBuilder()
	                    .add("prompts", Json.createArrayBuilder()
	                            .add(Json.createObjectBuilder()
	                                    .add("name", "explain_solution")
	                                    .add("description", "Explain the next logical step or full solution for a given Sudoku.")
	                                    .add("arguments", Json.createArrayBuilder()
	                                            .add(Json.createObjectBuilder()
	                                                    .add("name", "board")
	                                                    .add("type", "json")))))
	                    .build();
	            return okEnvelope(id, result);

	        case "prompts/get": {
	            final JsonObject params = request.getJsonObject("params");
	            final String promptName = params.getString("name", "");
	            if (!"explain_solution".equals(promptName)) {
	            	return errorEnvelope(id, -32601, "Unknown prompt: " + promptName);
	            }
	            // Return templated messages (system + user). Clients will substitute args.
	            result = Json.createObjectBuilder()
	                    .add("name", "explain_solution")
	                    .add("messages", Json.createArrayBuilder()
	                            .add(Json.createObjectBuilder()
	                                    .add("role", "system")
	                                    .add("content", "You are an expert Sudoku explainer. Be concise and step-by-step."))
	                            .add(Json.createObjectBuilder()
	                                    .add("role", "user")
	                                    .add("content", "Here is the current 9x9 board (0=blank). Explain the next step and why, or the full solution if forced:\n{{board}}")))
	                    .build();
	            return okEnvelope(id, result);
	        }

	        /* ----------------------- Resources ----------------------- */
	        case "resources/list":
	            // A couple of example puzzles as read-only resources
	            result = Json.createObjectBuilder()
	                    .add("resources", Json.createArrayBuilder()
	                            .add(Json.createObjectBuilder()
	                                    .add("uri", "sudoku://examples/easy-1")
	                                    .add("name", "Easy example #1")
	                                    .add("mimeType", "application/json"))
	                            .add(Json.createObjectBuilder()
	                                    .add("uri", "sudoku://examples/medium-1")
	                                    .add("name", "Medium example #1")
	                                    .add("mimeType", "application/json"))
	                            .add(Json.createObjectBuilder()
	                                    .add("uri", "sudoku://examples/hard-1")
	                                    .add("name", "Hard example #1")
	                                    .add("mimeType", "application/json"))
	                            .add(Json.createObjectBuilder()
	                                    .add("uri", "sudoku://examples/expert-1")
	                                    .add("name", "Expert example #1")
	                                    .add("mimeType", "application/json"))
	                            .add(Json.createObjectBuilder()
	                                    .add("uri", "sudoku://examples/master-1")
	                                    .add("name", "Master example #1")
	                                    .add("mimeType", "application/json"))
	                            .add(Json.createObjectBuilder()
	                                    .add("uri", "sudoku://examples/extreme-1")
	                                    .add("name", "Extreme example #1")
	                                    .add("mimeType", "application/json")))
	                    .build();
	            return okEnvelope(id, result);

	        case "resources/read": {
	            final JsonObject params = request.getJsonObject("params");
	            final String uri = params.getString("uri", "");

	            var maybeExample = ExampleSudoku.findByName.apply(uri, nameToUri);

	            if (maybeExample.isEmpty()) {
		            return errorEnvelope(id, -32601, "Unknown resource: " + uri);
	            }

	            var board = maybeExample.getBoard();
				var boardJson = boardToJson(board);

	            result = Json.createObjectBuilder()
	                    .add("uri", uri)
	                    .add("mimeType", "application/json")
	                    .add("contents", Json.createArrayBuilder()
	                            .add(Json.createObjectBuilder()
	                                    .add("uri", uri)
	                                    .add("mimeType", "application/json")
	                                    .add("text", Json.createObjectBuilder()
	                                            .add("board", boardJson)
	                                            .build()
	                                            .toString())))
	                    .build();
	            return okEnvelope(id, result);
	        }
	        default:
	        	return errorEnvelope(id, -32601, "Method not found: " + method);
	        }
        } catch (Throwable t) {
            t.printStackTrace();
            return errorEnvelope(id, -32000, "Server error: " + t.getMessage());
        }
    }
    @Path("/health")
    class HealthResource {
      @GET @Produces(MediaType.TEXT_PLAIN)
      public String ok() { return "ok"; }
    }
    /* -------------------- JSON helpers -------------------- */

    private JsonObject okEnvelope(int id, JsonObject result) {
      return Json.createObjectBuilder()
          .add("jsonrpc", "2.0")
          .add("id", id)
          .add("result", result)
          .build();
    }

    private JsonObject errorEnvelope(int id, int code, String message) {
      return Json.createObjectBuilder()
          .add("jsonrpc", "2.0")
          .add("id", id)
          .add("error", Json.createObjectBuilder()
              .add("code", code)
              .add("message", message))
          .build();
    }
    /* ---- Logging helper ---- */
    private void logProbe(String method, UriInfo ui, HttpHeaders h) {
      System.out.println(method+" "+ui.getRequestUri()+" Accept="+h.getHeaderString("Accept")+
          " Content-Type="+h.getHeaderString("Content-Type"));
    }
    private static int[][] jsonToBoard(JsonArray arr) {
        int[][] board = new int[9][9];
        for (int r = 0; r < 9; r++) {
            JsonArray row = arr.getJsonArray(r);
            for (int c = 0; c < 9; c++) board[r][c] = row.getInt(c);
        }
        return board;
    }

    private static JsonArray boardToJson(int[][] board) {
        JsonArrayBuilder ab = Json.createArrayBuilder();
        for (int r = 0; r < 9; r++) {
            JsonArrayBuilder rb = Json.createArrayBuilder();
            for (int c = 0; c < 9; c++) rb.add(board[r][c]);
            ab.add(rb);
        }
        return ab.build();
    }
}