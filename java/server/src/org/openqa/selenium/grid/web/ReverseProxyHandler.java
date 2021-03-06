// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.grid.web;

import com.google.common.collect.ImmutableSet;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.tag.Tags;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.tracing.HttpTracing;

import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.logging.Logger;

public class ReverseProxyHandler implements HttpHandler {

  private final static Logger LOG = Logger.getLogger(ReverseProxyHandler.class.getName());

  private final static ImmutableSet<String> IGNORED_REQ_HEADERS = ImmutableSet.<String>builder()
      .add("connection")
      .add("keep-alive")
      .add("proxy-authorization")
      .add("proxy-authenticate")
      .add("proxy-connection")
      .add("te")
      .add("trailer")
      .add("transfer-encoding")
      .add("upgrade")
      .build();

  private final Tracer tracer;
  private final HttpClient upstream;

  public ReverseProxyHandler(Tracer tracer, HttpClient httpClient) {
    this.tracer = Objects.requireNonNull(tracer, "Tracer must be set.");
    this.upstream = Objects.requireNonNull(httpClient, "HTTP client to use must be set.");
  }

  @Override
  public HttpResponse execute(HttpRequest req) throws UncheckedIOException {
    Span previous = tracer.scopeManager().activeSpan();
    SpanContext parent = HttpTracing.extract(tracer, req);
    Span span = tracer.buildSpan("reverse_proxy").asChildOf(parent).start();
    try {
      tracer.scopeManager().activate(span);

      span.setTag(Tags.HTTP_METHOD, req.getMethod().toString());
      span.setTag(Tags.HTTP_URL, req.getUri());

      HttpRequest toUpstream = new HttpRequest(req.getMethod(), req.getUri());

      for (String name : req.getQueryParameterNames()) {
        for (String value : req.getQueryParameters(name)) {
          toUpstream.addQueryParameter(name, value);
        }
      }

      for (String name : req.getHeaderNames()) {
        if (IGNORED_REQ_HEADERS.contains(name.toLowerCase())) {
          continue;
        }

        for (String value : req.getHeaders(name)) {
          toUpstream.addHeader(name, value);
        }
      }
      // None of this "keep alive" nonsense.
      toUpstream.setHeader("Connection", "keep-alive");

      toUpstream.setContent(req.getContent());
      HttpResponse resp = upstream.execute(toUpstream);

      span.setTag(Tags.HTTP_STATUS, resp.getStatus());

      // clear response defaults.
      resp.removeHeader("Date");
      resp.removeHeader("Server");

      IGNORED_REQ_HEADERS.forEach(resp::removeHeader);

      return resp;
    } finally {
      span.finish();
      tracer.scopeManager().activate(previous);
    }
  }
}
