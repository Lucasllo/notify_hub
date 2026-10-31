package dev.notifyhub.api.web.dto;

import java.util.List;

/**
 * Envelope da listagem paginada (D-09): {@code {content, page, size, totalElements, totalPages}}.
 *
 * <p>É um envelope próprio, e não o {@code PageImpl} do Spring Data serializado direto, porque o
 * formato JSON do {@code PageImpl} não é estável entre versões. Este formato é o contrato da API.
 *
 * @param content       itens da página pedida (vazio além da última página)
 * @param page          número da página, a partir de 0
 * @param size          tamanho de página usado
 * @param totalElements total de documentos que casam com o filtro, antes de skip/limit
 * @param totalPages    número de páginas com esse tamanho (0 quando não há documentos)
 * @param <T>           tipo do item
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
}
