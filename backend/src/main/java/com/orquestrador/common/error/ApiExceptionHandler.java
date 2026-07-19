package com.orquestrador.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Set;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("forbidden", exception.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("not_found", exception.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("conflict", exception.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("bad_request", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("validation_error", message));
    }

    /**
     * Erro do CLIENTE, não do servidor: corpo ilegível (JSON malformado, enum com typo tipo
     * {@code decision:"APROVE"}) → 400 — sem stack trace no log de erro (poluiria o monitoramento)
     * e sem ecoar o corpo ofensivo na resposta.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("bad_request", "Corpo da requisicao invalido ou valor fora do contrato"));
    }

    /** Query/path param com tipo inválido (ex.: ?status=NOPE num enum, UUID malformado) → 400. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("bad_request", "Parametro invalido: " + exception.getName()));
    }

    /**
     * Rota inexistente (URL com typo, bot varrendo, cliente velho batendo em endpoint removido)
     * → 404, não 500: quem pediu o que não existe não quebrou o servidor. Sem o handler, isto caía
     * no {@link #handleGeneric} e virava ERROR com stack trace inteiro para o que é tráfego normal.
     *
     * <p>São DUAS exceções para o mesmo evento, e qual delas chega depende de uma property:
     * com o mapeamento de estáticos ligado (default, o caso deste app) o {@code /**} do
     * ResourceHttpRequestHandler pega a URL órfã e lança {@code NoResourceFoundException}; com
     * {@code spring.web.resources.add-mappings=false} não há esse catch-all e o DispatcherServlet
     * lança {@code NoHandlerFoundException} (desde o Boot 3.2 o {@code throwExceptionIfNoHandlerFound}
     * já vem ligado). Ambas foram medidas devolvendo 500 antes desta correção — por isso as duas
     * entram aqui, e não só a que o default de hoje produz.
     *
     * <p>Método e caminho saem do request (e não da exceção) porque cada uma expõe o seu por uma
     * API diferente; o log é debug e sem exceção anexada — o stack trace de "essa rota não existe"
     * não informa nada e só afoga o monitoramento.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNoRoute(HttpServletRequest request) {
        log.debug("Rota inexistente: {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("not_found", "Rota inexistente: " + request.getMethod() + " " + request.getRequestURI()));
    }

    /**
     * Método HTTP errado numa rota que EXISTE (DELETE num endpoint só de GET, POST onde só há
     * PATCH) → 405, não 500: o servidor está inteiro, quem errou foi o verbo. É o mesmo buraco do
     * {@link #handleNoRoute} — sem {@code @ExceptionHandler} próprio isto caía no
     * {@link #handleGeneric} e virava ERROR com stack trace inteiro para erro de CLIENTE.
     *
     * <p>O header {@code Allow} é OBRIGATÓRIO numa resposta 405 (RFC 9110 §15.5.6) e é o que diz
     * ao cliente qual verbo usar. Quem o preenchia era o handler default do Spring; ao interceptar
     * a exceção aqui nós passamos a ser os responsáveis por ele — daí o
     * {@code getSupportedHttpMethods()}, que é anulável e por isso vem testado.
     *
     * <p>Log em debug e sem exceção anexada, pela mesma razão do 404.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception,
                                                           HttpServletRequest request) {
        Set<HttpMethod> supported = exception.getSupportedHttpMethods();
        log.debug("Metodo nao suportado: {} {} (aceitos: {})", request.getMethod(),
                request.getRequestURI(), supported);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (supported != null && !supported.isEmpty()) {
            response.allow(supported.toArray(new HttpMethod[0]));
        }
        return response.body(new ApiError("method_not_allowed",
                "Metodo nao suportado: " + request.getMethod() + " " + request.getRequestURI()));
    }

    /**
     * Corpo num {@code Content-Type} que a rota não consome (ex.: {@code text/plain} num endpoint
     * que só lê JSON) → 415. Mesmo buraco, mesma razão.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        log.debug("Content-Type nao suportado: {} (aceitos: {})", exception.getContentType(),
                exception.getSupportedMediaTypes());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiError("unsupported_media_type",
                        "Content-Type nao suportado: " + exception.getContentType()));
    }

    /**
     * {@code Accept} que a API não produz (ex.: {@code application/xml}) → 406, terceiro caso do
     * mesmo buraco.
     *
     * <p>O {@code Content-Type} explícito NÃO é decoração, e o porquê foi MEDIDO: antes desta
     * correção o 406 já respondia 406 — mas por acidente. A exceção caía no {@link #handleGeneric}
     * (logando ERROR com stack trace, o sintoma real), que montava um 500 com corpo
     * {@code ApiError}; ao ESCREVER esse corpo a negociação rodava de novo, falhava porque o
     * cliente pediu XML, e o Spring degradava para um 406 vazio. Ou seja: era o mesmo buraco do
     * 405 e do 415, apenas disfarçado pelo status final.
     *
     * <p>Fixar o tipo faz o conversor pular a negociação e o corpo {@code {error, message}} sair
     * de fato. Por isso o teste deste caso assere o CORPO e não só o status: um teste que olhasse
     * só o 406 teria passado antes da correção, sem testar nada.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> handleNotAcceptable(HttpMediaTypeNotAcceptableException exception) {
        log.debug("Accept incompativel com o que a rota produz (suportados: {})",
                exception.getSupportedMediaTypes());
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError("not_acceptable",
                        "Nenhum formato aceito por este cliente; a API responde application/json"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception exception) {
        log.error("Unhandled exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal_error", "Unexpected error"));
    }
}
