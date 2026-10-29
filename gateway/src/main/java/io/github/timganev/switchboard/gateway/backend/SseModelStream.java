package io.github.timganev.switchboard.gateway.backend;

import io.github.timganev.switchboard.commons.chat.StreamEvent;
import io.github.timganev.switchboard.gateway.format.OpenAiStreamReader;
import org.springframework.http.client.ClientHttpResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

class SseModelStream implements ModelStream {

    private final ClientHttpResponse response;
    private final OpenAiStreamReader streamReader;

    SseModelStream(ClientHttpResponse response, OpenAiStreamReader streamReader) {
        this.response = response;
        this.streamReader = streamReader;
    }

    @Override
    public void forEach(EventConsumer consumer) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring("data:".length()).strip();
            for (StreamEvent event : streamReader.read(data)) {
                consumer.accept(event);
            }
            if (data.equals("[DONE]")) {
                return;
            }
        }
        throw new IOException("The backend stream ended without [DONE]");
    }

    @Override
    public void close() {
        response.close();
    }
}
