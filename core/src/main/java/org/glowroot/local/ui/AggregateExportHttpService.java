/*
 * Copyright 2011-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.local.ui;

import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import com.google.common.io.Resources;
import com.google.common.net.MediaType;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.handler.stream.ChunkedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.local.ui.AggregateCommonService.MergedAggregate;
import org.glowroot.markers.Singleton;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Http service to export a trace as a complete html page, bound to /export/transaction. It is not
 * bound under /backend since it is visible to users as the download url for the export file.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@VisibleForTesting
@Singleton
public class AggregateExportHttpService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(AggregateExportHttpService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final AggregateCommonService aggregateCommonService;

    AggregateExportHttpService(AggregateCommonService aggregateCommonService) {
        this.aggregateCommonService = aggregateCommonService;
    }

    @Override
    @Nullable
    public HttpResponse handleRequest(HttpRequest request, Channel channel) throws IOException {
        QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());
        List<String> transactionTypeParameters = decoder.getParameters().get("transaction-type");
        if (transactionTypeParameters == null) {
            throw new IllegalArgumentException(
                    "Missing required query parameter: transaction-type");
        }
        String transactionType = transactionTypeParameters.get(0);
        List<String> transactionNameParameters = decoder.getParameters().get("transaction-name");
        String transactionName = null;
        if (transactionNameParameters != null) {
            transactionName = transactionNameParameters.get(0);
        }
        List<String> fromParameters = decoder.getParameters().get("from");
        if (fromParameters == null) {
            throw new IllegalArgumentException("Missing required query parameter: from");
        }
        long from = Long.parseLong(fromParameters.get(0));
        List<String> toParameters = decoder.getParameters().get("to");
        if (toParameters == null) {
            throw new IllegalArgumentException("Missing required query parameter: to");
        }
        long to = Long.parseLong(toParameters.get(0));
        MergedAggregate mergedAggregate = aggregateCommonService.getMergedAggregate(
                transactionType, transactionName, from, to);
        AggregateProfileNode profile = null;
        if (transactionName != null) {
            profile = aggregateCommonService.getProfile(transactionType, transactionName, from,
                    to, 0.001);
        }
        ChunkedInput in = getExportChunkedInput(mergedAggregate, profile);
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.headers().set(CONTENT_TYPE, MediaType.ZIP.toString());
        response.headers().set("Content-Disposition",
                "attachment; filename=" + getFilename(mergedAggregate) + ".zip");
        HttpServices.preventCaching(response);
        response.setChunked(true);
        channel.write(response);
        channel.write(in);
        // return null to indicate streaming
        return null;
    }

    private ChunkedInput getExportChunkedInput(MergedAggregate mergedAggregate,
            @Nullable AggregateProfileNode profile) throws IOException {
        CharSource charSource = render(mergedAggregate, profile);
        return ChunkedInputs.fromReaderToZipFileDownload(charSource.openStream(),
                getFilename(mergedAggregate));
    }

    private static String getFilename(MergedAggregate mergedAggregate) {
        String timestamp =
                new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(mergedAggregate.getTo());
        String transactionName = mergedAggregate.getTransactionName();
        if (transactionName == null) {
            return "performance-all-" + timestamp;
        }
        CharMatcher filenameSafeCharMatcher =
                CharMatcher.inRange('a', 'z').or(CharMatcher.inRange('A', 'Z'));
        String filenameSafeTransactionName = filenameSafeCharMatcher.retainFrom(transactionName);
        return "performance-" + filenameSafeTransactionName + '-' + timestamp;
    }

    private static CharSource render(MergedAggregate mergedAggregate,
            @Nullable AggregateProfileNode profile) throws IOException {
        String exportCssPlaceholder = "<link rel=\"stylesheet\" href=\"styles/export-main.css\">";
        String exportComponentsJsPlaceholder = "<script src=\"scripts/export-vendor.js\"></script>";
        String exportJsPlaceholder =
                "<script src=\"scripts/export-aggregate-scripts.js\"></script>";
        String aggregatePlaceholder = "<script type=\"text/json\" id=\"aggregateJson\"></script>";
        String profilePlaceholder = "<script type=\"text/json\" id=\"profileJson\"></script>";

        String templateContent = asCharSource("aggregate-export.html").read();
        Pattern pattern = Pattern.compile("(" + exportCssPlaceholder + "|"
                + exportComponentsJsPlaceholder + "|" + exportJsPlaceholder + "|"
                + aggregatePlaceholder + "|" + profilePlaceholder + ")");
        Matcher matcher = pattern.matcher(templateContent);
        int curr = 0;
        List<CharSource> charSources = Lists.newArrayList();
        while (matcher.find()) {
            charSources.add(CharSource.wrap(templateContent.substring(curr, matcher.start())));
            curr = matcher.end();
            String match = matcher.group();
            if (match.equals(exportCssPlaceholder)) {
                charSources.add(CharSource.wrap("<style>"));
                charSources.add(asCharSource("styles/export-main.css"));
                charSources.add(CharSource.wrap("</style>"));
            } else if (match.equals(exportComponentsJsPlaceholder)) {
                charSources.add(CharSource.wrap("<script>"));
                charSources.add(asCharSource("scripts/export-vendor.js"));
                charSources.add(CharSource.wrap("</script>"));
            } else if (match.equals(exportJsPlaceholder)) {
                charSources.add(CharSource.wrap("<script>"));
                charSources.add(asCharSource("scripts/export-aggregate-scripts.js"));
                charSources.add(CharSource.wrap("</script>"));
            } else if (match.equals(aggregatePlaceholder)) {
                charSources.add(CharSource.wrap(
                        "<script type=\"text/json\" id=\"aggregateJson\">"));
                charSources.add(CharSource.wrap(mapper.writeValueAsString(mergedAggregate)));
                charSources.add(CharSource.wrap("</script>"));
            } else if (match.equals(profilePlaceholder)) {
                charSources.add(CharSource.wrap("<script type=\"text/json\" id=\"profileJson\">"));
                if (profile != null) {
                    charSources.add(CharSource.wrap(mapper.writeValueAsString(profile)));
                }
                charSources.add(CharSource.wrap("</script>"));
            } else {
                logger.error("unexpected match: {}", match);
            }
        }
        charSources.add(CharSource.wrap(templateContent.substring(curr)));
        return CharSource.concat(charSources);
    }

    private static CharSource asCharSource(String exportResourceName) {
        URL url = Resources.getResource("org/glowroot/local/ui/export-dist/" + exportResourceName);
        return Resources.asCharSource(url, Charsets.UTF_8);
    }
}