package com.ecomdemo.order.client;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What order-service needs to know about a product: what it is called and what it costs.
 *
 * <h2>Why this is not catalog-service's ProductResponse</h2>
 *
 * It would have been easy to put that record in shared-kernel and have both services compile against
 * it. That is the single most common way a set of microservices quietly turns back into a monolith:
 * the shared class becomes a shared domain model, changing it means recompiling and redeploying
 * everyone who depends on it, and the independent deployability the split was for is gone.
 *
 * <p>So this service declares the shape <em>it</em> needs. catalog-service's response has five
 * fields; this has three, because three are all a checkout uses. The two are related by a convention
 * - the JSON field names - and by a test on each side, not by a compiler.
 *
 * <h2>Tolerant reader</h2>
 *
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} is the whole versioning strategy, and it is
 * doing real work rather than being defensive boilerplate. It means catalog-service can add a field
 * to its response and deploy whenever it likes, without waiting for this service - the new field is
 * simply ignored here. Without it, every additive change on one side would be a breaking change on
 * the other, and the two services would have to be released together.
 *
 * <p>Note the package: {@code com.fasterxml.jackson.annotation}, not {@code tools.jackson}. Jackson 3
 * moved databind to {@code tools.jackson} - which is why {@code ApiErrorResponder} imports
 * {@code tools.jackson.databind.ObjectMapper} - but jackson-annotations stayed where it was. Mixing
 * the two up gives a "package does not exist" that looks like a missing dependency.
 *
 * <p>What it does <em>not</em> tolerate, and nothing could: catalog-service renaming {@code price} or
 * removing it. Additive changes are safe; a rename is two deploys - add the new name, wait for every
 * reader to move, then remove the old one. Expand and contract, exactly as Phase 5 applied it to
 * database columns, now applied to a JSON contract.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogProduct(Long id, String name, BigDecimal price) {
}
