package io.github.mocchikon.hentie;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Just enough of ComfyUI's HTTP API and websocket to test the app's client against.
 *
 * <p>A fake rather than a mock of the client, because the protocol <i>is</i> what these suites test. Raw
 * sockets, because the JDK's {@code HttpServer} cannot upgrade a connection to a websocket. Each test decides
 * what a queued prompt does through {@link #onPrompt}.
 */
public final class FakeComfyUi implements AutoCloseable
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public sealed interface Behaviour permits Succeed, Reject, Fail, Hold, Wait {}

    public record Succeed(byte[] png) implements Behaviour {}

    /** Refused at validation (HTTP 400), as for a model file that is not installed. */
    public record Reject(String nodeId, String message, String details) implements Behaviour {}

    /** Accepted, then an {@code execution_error} while running. */
    public record Fail(String nodeId, String nodeType, String exceptionMessage) implements Behaviour {}

    /** Runs until {@code release} opens (then succeeds) or until {@code /interrupt} names it. */
    public record Hold(CountDownLatch release, byte[] png) implements Behaviour {}

    /**
     * Waits in the queue, behind someone else's prompt, until {@code start} opens, then succeeds. Deleted from
     * the queue meanwhile, it never runs and says nothing, as in ComfyUI.
     */
    public record Wait(CountDownLatch start, byte[] png) implements Behaviour {}

    private final ServerSocket server;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, UserFile> userFiles = new ConcurrentHashMap<>();
    private final Map<String, byte[]> uploads = new ConcurrentHashMap<>();
    private final Map<String, byte[]> tempFiles = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> history = new ConcurrentHashMap<>();
    private final Map<String, Client> sockets = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> prompts = new CopyOnWriteArrayList<>();
    private final List<String> interrupted = new CopyOnWriteArrayList<>();
    private final List<String> deleted = new CopyOnWriteArrayList<>();
    /** Running {@link Hold} prompts by id, each with the latch that stops it. */
    private final Map<String, CountDownLatch> holding = new ConcurrentHashMap<>();
    private final List<String> waiting = new CopyOnWriteArrayList<>();
    private final AtomicLong uploadWrites = new AtomicLong();

    private record UserFile(byte[] content, long modifiedMillis) {}

    /** Whether {@code SaveImageWebsocket} is "installed". */
    public volatile boolean websocketNode = true;

    public volatile Function<JsonNode, Behaviour> onPrompt = prompt -> new Succeed(TestImages.png(8, 8));

    public FakeComfyUi() throws IOException
    {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        threads.submit(this::acceptLoop);
    }

    public String url()
    {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    @Override
    public void close() throws IOException
    {
        server.close();
        sockets.values().forEach(Client::closeQuietly);
        threads.shutdownNow();
    }

    // ---- what a test sets up and looks at ---------------------------------

    /** Puts a file into the user folder, e.g. {@code "api_workflows/upscale.json"}. */
    public void putUserFile(String path, String content)
    {
        putUserFile(path, content, System.currentTimeMillis());
    }

    public void putUserFile(String path, String content, long modifiedMillis)
    {
        userFiles.put(path, new UserFile(content.getBytes(StandardCharsets.UTF_8), modifiedMillis));
    }

    public void removeUserFile(String path)
    {
        userFiles.remove(path);
    }

    /** As {@code "METHOD /path"}, without the query. */
    public List<String> requests()
    {
        return List.copyOf(requests);
    }

    public long count(String methodAndPath)
    {
        return requests.stream().filter(methodAndPath::equals).count();
    }

    public List<JsonNode> prompts()
    {
        return List.copyOf(prompts);
    }

    /** The bytes the prompt's Load Image node was pointed at. */
    public byte[] inputOf(JsonNode payload)
    {
        for (JsonNode node : payload.path("prompt"))
        {
            if ("LoadImage".equals(node.path("class_type").asText()))
            {
                String reference = node.path("inputs").path("image").asText();
                return uploads.get(reference.replace(" [temp]", ""));
            }
        }
        return null;
    }

    /** An upload of a name and content already there is not counted, as it is not written. */
    public long uploadWrites()
    {
        return uploadWrites.get();
    }

    public List<String> interrupted()
    {
        return List.copyOf(interrupted);
    }

    public List<String> deleted()
    {
        return List.copyOf(deleted);
    }

    public List<String> holding()
    {
        return List.copyOf(holding.keySet());
    }

    public List<String> waiting()
    {
        return List.copyOf(waiting);
    }

    public void clearRecords()
    {
        requests.clear();
        prompts.clear();
        interrupted.clear();
        deleted.clear();
    }

    // ---- HTTP -------------------------------------------------------------

    private record Request(String method, String path, Map<String, String> query, Map<String, String> headers,
                           byte[] body) {}

    private record Response(int status, String contentType, byte[] body) {}

    private void acceptLoop()
    {
        while (!server.isClosed())
        {
            try
            {
                Socket socket = server.accept();
                threads.submit(() -> handle(socket));
            }
            catch (IOException e)
            {
                return;
            }
        }
    }

    private void handle(Socket socket)
    {
        try
        {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            Request request = readRequest(in);
            if (request == null)
            {
                socket.close();
                return;
            }
            requests.add(request.method() + " " + request.path());
            if ("websocket".equalsIgnoreCase(request.headers().get("upgrade")))
            {
                websocket(request, socket, in, out);
                return;
            }
            Runnable after = null;
            Response response;
            if (request.method().equals("POST") && request.path().equals("/prompt"))
            {
                var queued = queue(request);
                response = queued.response();
                after = queued.run();
            }
            else
            {
                response = route(request);
            }
            write(out, response);
            socket.close();
            if (after != null)
            {
                after.run();
            }
        }
        catch (IOException | RuntimeException e)
        {
            try
            {
                socket.close();
            }
            catch (IOException ignored)
            {
                // gone already
            }
        }
    }

    private Response route(Request request)
    {
        String path = request.path();
        if (request.method().equals("GET") && path.equals("/system_stats"))
        {
            return json(200, "{\"system\":{\"comfyui_version\":\"0.99.0-fake\"},"
                    + "\"devices\":[{\"name\":\"fake-gpu\",\"type\":\"cuda\"}]}");
        }
        if (request.method().equals("GET") && path.equals("/userdata"))
        {
            return listUserFiles(request.query().getOrDefault("dir", ""));
        }
        if (request.method().equals("GET") && path.startsWith("/userdata/"))
        {
            UserFile file = userFiles.get(decode(path.substring("/userdata/".length())));
            return file == null ? new Response(404, "text/plain", new byte[0]) : new Response(200, "application/json", file.content());
        }
        if (request.method().equals("GET") && path.startsWith("/object_info/"))
        {
            String type = path.substring("/object_info/".length());
            boolean present = !type.equals("SaveImageWebsocket") || websocketNode;
            return json(200, present ? "{\"" + type + "\":{\"output_node\":true}}" : "{}");
        }
        if (request.method().equals("POST") && path.equals("/upload/image"))
        {
            return upload(request);
        }
        if (request.method().equals("GET") && path.startsWith("/history/"))
        {
            String id = decode(path.substring("/history/".length()));
            JsonNode entry = history.get(id);
            ObjectNode answer = JSON.createObjectNode();
            if (entry != null)
            {
                answer.set(id, entry);
            }
            return json(200, answer.toString());
        }
        if (request.method().equals("GET") && path.equals("/view"))
        {
            byte[] file = tempFiles.get(request.query().getOrDefault("filename", ""));
            return file == null ? new Response(404, "text/plain", new byte[0]) : new Response(200, "image/png", file);
        }
        if (request.method().equals("GET") && path.equals("/queue"))
        {
            // ComfyUI's entries start [number, prompt_id, ...], and the client reads no further.
            ObjectNode answer = JSON.createObjectNode();
            ArrayNode running = answer.putArray("queue_running");
            holding.keySet().forEach(id -> running.addArray().add(0).add(id));
            ArrayNode pending = answer.putArray("queue_pending");
            waiting.forEach(id -> pending.addArray().add(0).add(id));
            return json(200, answer.toString());
        }
        if (request.method().equals("POST") && path.equals("/queue"))
        {
            body(request).path("delete").forEach(id -> deleted.add(id.asText()));
            return new Response(200, "text/plain", new byte[0]);
        }
        if (request.method().equals("POST") && path.equals("/interrupt"))
        {
            // As ComfyUI does with a prompt id: only that prompt stops, and only if it is running.
            String promptId = body(request).path("prompt_id").asText("");
            interrupted.add(promptId);
            CountDownLatch stop = holding.get(promptId);
            if (stop != null)
            {
                stop.countDown();
            }
            return new Response(200, "text/plain", new byte[0]);
        }
        return new Response(404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
    }

    private Response listUserFiles(String dir)
    {
        String prefix = dir + "/";
        ArrayNode files = JSON.createArrayNode();
        userFiles.forEach((path, file) ->
        {
            if (path.startsWith(prefix))
            {
                files.addObject().put("path", path.substring(prefix.length())).put("size", file.content().length)
                        .put("modified", file.modifiedMillis()).put("created", file.modifiedMillis());
            }
        });
        return files.isEmpty() ? new Response(404, "text/plain", "Directory not found".getBytes(StandardCharsets.UTF_8))
                : json(200, files.toString());
    }

    /** Like ComfyUI, skips writing an upload whose name and content are already there. */
    private Response upload(Request request)
    {
        Map<String, Part> parts = multipart(request);
        Part image = parts.get("image");
        if (image == null || image.filename() == null)
        {
            return new Response(400, "text/plain", new byte[0]);
        }
        byte[] existing = uploads.get(image.filename());
        if (existing == null || !Arrays.equals(existing, image.content()))
        {
            uploads.put(image.filename(), image.content());
            uploadWrites.incrementAndGet();
        }
        return json(200, JSON.createObjectNode().put("name", image.filename()).put("subfolder", "")
                .put("type", "temp").toString());
    }

    private record Queued(Response response, Runnable run) {}

    private Queued queue(Request request)
    {
        JsonNode payload;
        try
        {
            payload = JSON.readTree(request.body());
        }
        catch (IOException e)
        {
            return new Queued(json(400, "{\"error\":{\"message\":\"bad json\"}}"), null);
        }
        prompts.add(payload);
        String promptId = payload.path("prompt_id").asText();
        String clientId = payload.path("client_id").asText();
        String outputId = payload.path("partial_execution_targets").path(0).asText();
        Behaviour behaviour = onPrompt.apply(payload);
        if (behaviour instanceof Reject reject)
        {
            ObjectNode answer = JSON.createObjectNode();
            answer.putObject("error").put("type", "prompt_outputs_failed_validation")
                    .put("message", "Prompt outputs failed validation");
            ObjectNode error = answer.putObject("node_errors").putObject(reject.nodeId());
            error.putArray("errors").addObject().put("message", reject.message()).put("details", reject.details());
            return new Queued(json(400, answer.toString()), null);
        }
        Response accepted = json(200, JSON.createObjectNode().put("prompt_id", promptId).put("number", prompts.size())
                .set("node_errors", JSON.createObjectNode()).toString());
        return new Queued(accepted, () -> threads.submit(() -> execute(behaviour, payload, promptId, clientId, outputId)));
    }

    private void execute(Behaviour behaviour, JsonNode payload, String promptId, String clientId, String outputId)
    {
        Client client = sockets.get(clientId);
        if (client == null)
        {
            return;
        }
        if (behaviour instanceof Wait wait && !waitedForItsTurn(wait, promptId))
        {
            return;
        }
        client.text(event("status", JSON.createObjectNode().set("status",
                JSON.createObjectNode().set("exec_info", JSON.createObjectNode().put("queue_remaining", 1)))));
        client.text(event("execution_start", JSON.createObjectNode().put("prompt_id", promptId)));
        JsonNode prompt = payload.path("prompt");
        for (var entry : prompt.properties())
        {
            if (entry.getKey().equals(outputId))
            {
                continue;
            }
            client.text(event("executing", JSON.createObjectNode().put("node", entry.getKey()).put("prompt_id", promptId)));
            client.text(event("progress", JSON.createObjectNode().put("value", 1).put("max", 2)
                    .put("node", entry.getKey()).put("prompt_id", promptId)));
            if (behaviour instanceof Fail fail && fail.nodeId().equals(entry.getKey()))
            {
                client.text(event("execution_error", JSON.createObjectNode().put("prompt_id", promptId)
                        .put("node_id", fail.nodeId()).put("node_type", fail.nodeType())
                        .put("exception_message", fail.exceptionMessage())));
                client.text(event("executing", JSON.createObjectNode().putNull("node").put("prompt_id", promptId)));
                return;
            }
        }
        byte[] png;
        if (behaviour instanceof Hold hold)
        {
            var stop = new CountDownLatch(1);
            holding.put(promptId, stop);
            try
            {
                // A cancel can race the queueing and arrive before this point.
                if (deleted.contains(promptId) || interrupted.contains(promptId) || !released(hold.release(), stop))
                {
                    client.text(event("execution_interrupted", JSON.createObjectNode().put("prompt_id", promptId)));
                    return;
                }
            }
            catch (InterruptedException e)
            {
                return;
            }
            finally
            {
                holding.remove(promptId);
            }
            png = hold.png();
        }
        else if (behaviour instanceof Wait wait)
        {
            png = wait.png();
        }
        else
        {
            png = ((Succeed) behaviour).png();
        }
        client.text(event("executing", JSON.createObjectNode().put("node", outputId).put("prompt_id", promptId)));
        if ("SaveImageWebsocket".equals(prompt.path(outputId).path("class_type").asText()))
        {
            var frame = new ByteArrayOutputStream();
            frame.writeBytes(new byte[] {0, 0, 0, 1, 0, 0, 0, 2});
            frame.writeBytes(png);
            client.binary(frame.toByteArray());
        }
        else
        {
            String name = "ComfyUI_temp_" + promptId.substring(0, 8) + "_00001_.png";
            tempFiles.put(name, png);
            ObjectNode images = JSON.createObjectNode();
            images.putArray("images").addObject().put("filename", name).put("subfolder", "").put("type", "temp");
            ObjectNode outputs = JSON.createObjectNode();
            outputs.set(outputId, images);
            history.put(promptId, JSON.createObjectNode().set("outputs", outputs));
            client.text(event("executed", JSON.createObjectNode().put("node", outputId).put("prompt_id", promptId)
                    .set("output", images)));
        }
        client.text(event("execution_success", JSON.createObjectNode().put("prompt_id", promptId)));
        client.text(event("executing", JSON.createObjectNode().putNull("node").put("prompt_id", promptId)));
    }

    /** True once the prompt's turn comes; false once it is deleted from the queue, or after a minute of neither. */
    private boolean waitedForItsTurn(Wait wait, String promptId)
    {
        waiting.add(promptId);
        try
        {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline && !deleted.contains(promptId))
            {
                if (wait.start().await(25, TimeUnit.MILLISECONDS))
                {
                    return true;
                }
            }
            return false;
        }
        catch (InterruptedException e)
        {
            return false;
        }
        finally
        {
            waiting.remove(promptId);
        }
    }

    /** True once {@code release} opens; false once {@code stop} does, or after a minute of neither. */
    private static boolean released(CountDownLatch release, CountDownLatch stop) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline)
        {
            if (release.await(25, TimeUnit.MILLISECONDS))
            {
                return true;
            }
            if (stop.getCount() == 0)
            {
                return false;
            }
        }
        return false;
    }

    private static String event(String type, JsonNode data)
    {
        return JSON.createObjectNode().put("type", type).set("data", data).toString();
    }

    private static JsonNode body(Request request)
    {
        try
        {
            JsonNode node = JSON.readTree(request.body());
            return node == null ? JSON.createObjectNode() : node;
        }
        catch (IOException e)
        {
            return JSON.createObjectNode();
        }
    }

    // ---- websocket --------------------------------------------------------

    private void websocket(Request request, Socket socket, InputStream in, OutputStream out) throws IOException
    {
        String accept = Base64.getEncoder().encodeToString(sha1(request.headers().get("sec-websocket-key") + WEBSOCKET_GUID));
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String clientId = request.query().getOrDefault("clientId", "");
        var client = new Client(socket, out);
        sockets.put(clientId, client);
        client.text(event("status", JSON.createObjectNode().set("status",
                JSON.createObjectNode().set("exec_info", JSON.createObjectNode().put("queue_remaining", 0)))));
        try
        {
            // Client frames are read only to notice a close.
            while (true)
            {
                int first = in.read();
                int second = in.read();
                if (first < 0 || second < 0)
                {
                    return;
                }
                long length = second & 0x7f;
                if (length == 126)
                {
                    length = (in.read() << 8) | in.read();
                }
                else if (length == 127)
                {
                    length = 0;
                    for (int i = 0; i < 8; i++)
                    {
                        length = (length << 8) | in.read();
                    }
                }
                if ((second & 0x80) != 0)
                {
                    in.readNBytes(4);
                }
                in.readNBytes((int) length);
                if ((first & 0x0f) == 0x8)
                {
                    client.send(0x8, new byte[0]);
                    return;
                }
            }
        }
        finally
        {
            sockets.remove(clientId, client);
            client.closeQuietly();
        }
    }

    private static final class Client
    {
        private final Socket socket;
        private final OutputStream out;

        Client(Socket socket, OutputStream out)
        {
            this.socket = socket;
            this.out = out;
        }

        void text(String message)
        {
            send(0x1, message.getBytes(StandardCharsets.UTF_8));
        }

        void binary(byte[] payload)
        {
            send(0x2, payload);
        }

        synchronized void send(int opcode, byte[] payload)
        {
            try
            {
                out.write(0x80 | opcode);
                if (payload.length < 126)
                {
                    out.write(payload.length);
                }
                else if (payload.length < 65_536)
                {
                    out.write(126);
                    out.write(payload.length >> 8);
                    out.write(payload.length & 0xff);
                }
                else
                {
                    out.write(127);
                    for (int shift = 56; shift >= 0; shift -= 8)
                    {
                        out.write((int) ((long) payload.length >> shift) & 0xff);
                    }
                }
                out.write(payload);
                out.flush();
            }
            catch (IOException ignored)
            {
                // the client went away; so does this message
            }
        }

        void closeQuietly()
        {
            try
            {
                socket.close();
            }
            catch (IOException ignored)
            {
                // closed already
            }
        }
    }

    // ---- parsing ----------------------------------------------------------

    private static Request readRequest(InputStream in) throws IOException
    {
        var head = new ByteArrayOutputStream();
        int last4 = 0;
        int b;
        // The head ends at the first blank line: CR LF CR LF.
        while ((b = in.read()) >= 0)
        {
            head.write(b);
            last4 = (last4 << 8) | b;
            if (last4 == 0x0d0a0d0a)
            {
                break;
            }
        }
        String text = head.toString(StandardCharsets.US_ASCII);
        if (text.isBlank())
        {
            return null;
        }
        String[] lines = text.split("\r\n");
        String[] requestLine = lines[0].split(" ");
        var headers = new HashMap<String, String>();
        for (int i = 1; i < lines.length; i++)
        {
            int colon = lines[i].indexOf(':');
            if (colon > 0)
            {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
        }
        String target = requestLine[1];
        String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        var query = new HashMap<String, String>();
        if (target.contains("?"))
        {
            for (String pair : target.substring(target.indexOf('?') + 1).split("&"))
            {
                int eq = pair.indexOf('=');
                if (eq > 0)
                {
                    query.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
                }
            }
        }
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        byte[] body = in.readNBytes(length);
        return new Request(requestLine[0], path, query, headers, body);
    }

    private record Part(String filename, byte[] content) {}

    private static Map<String, Part> multipart(Request request)
    {
        String contentType = request.headers().getOrDefault("content-type", "");
        String boundary = "--" + contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
        byte[] body = request.body();
        byte[] delimiter = boundary.getBytes(StandardCharsets.US_ASCII);
        var parts = new HashMap<String, Part>();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + delimiter.length <= body.length; i++)
        {
            if (matches(body, i, delimiter))
            {
                starts.add(i);
            }
        }
        for (int p = 0; p + 1 < starts.size(); p++)
        {
            int from = starts.get(p) + delimiter.length + 2;
            int to = starts.get(p + 1) - 2;
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), from);
            String partHeaders = new String(body, from, headerEnd - from, StandardCharsets.UTF_8);
            byte[] content = Arrays.copyOfRange(body, headerEnd + 4, to);
            String name = attribute(partHeaders, "name");
            parts.put(name, new Part(attribute(partHeaders, "filename"), content));
        }
        return parts;
    }

    private static String attribute(String headers, String key)
    {
        String marker = " " + key + "=\"";
        int at = headers.indexOf(marker);
        if (at < 0)
        {
            marker = ";" + key + "=\"";
            at = headers.indexOf(marker);
        }
        if (at < 0)
        {
            return null;
        }
        int start = at + marker.length();
        return headers.substring(start, headers.indexOf('"', start));
    }

    private static boolean matches(byte[] data, int at, byte[] pattern)
    {
        for (int i = 0; i < pattern.length; i++)
        {
            if (data[at + i] != pattern[i])
            {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from)
    {
        for (int i = from; i + pattern.length <= data.length; i++)
        {
            if (matches(data, i, pattern))
            {
                return i;
            }
        }
        return -1;
    }

    private static void write(OutputStream out, Response response) throws IOException
    {
        out.write(("HTTP/1.1 " + response.status() + " X\r\nContent-Type: " + response.contentType()
                + "\r\nContent-Length: " + response.body().length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(response.body());
        out.flush();
    }

    private static Response json(int status, String body)
    {
        return new Response(status, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value)
    {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static byte[] sha1(String value)
    {
        try
        {
            return MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.US_ASCII));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
