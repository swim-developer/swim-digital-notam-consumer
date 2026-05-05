package com.github.swim_developer.dnotam.consumer.infrastructure.out.xml;

import com.github.swim_developer.framework.infrastructure.util.StringUtil;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@ApplicationScoped
public class DnotamXmlEnvelopeParser {

    private static final String CDATA_OPEN = "<![CDATA[";
    private static final int CDATA_OPEN_LENGTH = CDATA_OPEN.length();
    private static final Pattern MESSAGE_PATTERN = Pattern.compile("<message[^>]*>.*?</message>", Pattern.DOTALL);

    public List<String> splitEnvelope(String payload) {
        if (StringUtil.isNullOrBlank(payload)) {
            throw new IllegalArgumentException("Payload is null or empty");
        }

        String trimmed = payload.trim();

        if (trimmed.startsWith("<messages>")) {
            return extractFromMessagesEnvelope(trimmed);
        } else if (trimmed.startsWith("<?xml") || trimmed.startsWith("<message:AIXMBasicMessage")) {
            return List.of(trimmed);
        } else {
            log.warn("Unknown payload format, attempting to use as-is");
            return List.of(trimmed);
        }
    }

    public static boolean hasEvent(String xmlPayload) {
        if (StringUtil.isNullOrBlank(xmlPayload)) {
            return false;
        }
        return xmlPayload.contains("<event:Event") || xmlPayload.contains(":Event ");
    }

    private List<String> extractFromMessagesEnvelope(String envelope) {
        List<String> aixmMessages = new ArrayList<>();
        Matcher matcher = MESSAGE_PATTERN.matcher(envelope);
        while (matcher.find()) {
            extractCdataFromMessage(matcher.group(), aixmMessages);
        }
        if (aixmMessages.isEmpty()) {
            log.warn("No AIXM messages extracted from <messages> envelope");
        }
        return aixmMessages;
    }

    private void extractCdataFromMessage(String message, List<String> aixmMessages) {
        int cdataStart = message.indexOf(CDATA_OPEN);
        int cdataEnd = message.indexOf("]]>");

        if (cdataStart >= 0 && cdataEnd > cdataStart) {
            String aixmXml = message.substring(cdataStart + CDATA_OPEN_LENGTH, cdataEnd).trim();
            aixmMessages.add(aixmXml);
        } else {
            log.warn("CDATA not found in message #{}, skipping", aixmMessages.size() + 1);
        }
    }
}
