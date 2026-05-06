package com.pjr22.tripweather.config;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.xml.MappingJackson2XmlHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Drops Jackson's XML message converter from Spring MVC's converter chain.
 *
 * The {@link XmlMapper} bean from {@code jackson-dataformat-xml} is used by
 * {@code WMSCapabilitiesService} to parse the static {@code conus_capabilities.xml}
 * upstream WMS response — that's a direct library use, not a Spring converter
 * use. Spring Boot, however, auto-registers {@link MappingJackson2XmlHttpMessageConverter}
 * the moment {@link XmlMapper} or {@link JacksonXmlModule} is on the classpath, which
 * makes XML eligible during content negotiation. Browsers send
 * {@code Accept: application/xhtml+xml;q=0.9} on every address-bar GET, so any
 * controller returning a {@code Map}, {@code List}, or POJO (without an explicit
 * {@code produces}) ends up serializing as XML for those clients.
 *
 * No endpoint in this app intentionally serves or consumes XML over HTTP, so
 * removing the converter from the chain on both directions is safe and gives
 * us a single source of truth: the API speaks JSON, period.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.removeIf(c -> c instanceof MappingJackson2XmlHttpMessageConverter);
    }
}
