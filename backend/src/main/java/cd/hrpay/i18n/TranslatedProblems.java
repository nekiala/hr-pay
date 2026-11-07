package cd.hrpay.i18n;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Error answers in the person's language: the message and each field's message. */
@RestControllerAdvice
class TranslatedProblems implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof ProblemDetail p && Texts.french()) {
            p.setDetail(Texts.translate(p.getDetail()));
            if (p.getProperties() != null && p.getProperties().get("errors") instanceof Map<?, ?> errors) {
                Map<String, String> out = new LinkedHashMap<>();
                errors.forEach((k, v) -> out.put(String.valueOf(k), Texts.translate(String.valueOf(v))));
                p.setProperty("errors", out);
            }
        }
        return body;
    }
}
