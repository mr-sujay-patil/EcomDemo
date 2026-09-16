package com.ecomdemo.inventory;

import java.util.List;

import com.ecomdemo.inventory.dto.ReservationRequest;
import com.ecomdemo.inventory.dto.StockLevelRequest;
import com.ecomdemo.inventory.dto.StockResponse;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The stock API.
 *
 * <p>Two audiences, which is why the authorization rules are not uniform: shoppers and the catalogue
 * page read availability, administrators set it, and order-service reserves and releases it on behalf
 * of a shopper who is checking out.
 *
 * <p>The reservation endpoints are POSTs to collection-shaped URLs rather than a verb like
 * {@code /api/stock/reserve}. That is a nudge rather than pedantry: a reservation <em>should</em> be a
 * resource with an id that can be looked up, retried idempotently and expired. It is not one here,
 * and {@link ReservationRequest} says so plainly. Naming it as if it were is a small, honest marker
 * of where Phase 24 has to change this.
 */
@RestController
@RequestMapping("/api/stock")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    /** Availability for one product. Public, like browsing the catalogue it describes. */
    @GetMapping("/{productId}")
    public StockResponse get(@PathVariable Long productId) {
        return stockService.findByProductId(productId);
    }

    /**
     * Availability for several products in one call.
     *
     * <p>{@code GET /api/stock?productIds=1,2,3}. A caller rendering a page of ten products makes one
     * request instead of ten - the difference between a loop and an N+1 once the loop crosses a
     * network.
     */
    @GetMapping
    public List<StockResponse> getMany(@RequestParam List<Long> productIds) {
        return stockService.findByProductIds(productIds);
    }

    /** An administrator setting an absolute figure. Creates the row on first use. */
    @PutMapping("/{productId}")
    public StockResponse set(@PathVariable Long productId,
                             @Valid @RequestBody StockLevelRequest request) {
        return stockService.setQuantity(productId, request);
    }

    /**
     * Takes a cart's worth of stock out, all of it or none.
     *
     * <p>204 rather than a body: there is nothing to return that the caller does not already know,
     * and no reservation id to hand back because no reservation is recorded. A 409 here is the normal
     * way a checkout fails - not enough stock, or too many concurrent attempts on the same row.
     */
    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reserve(@Valid @RequestBody ReservationRequest request) {
        stockService.reserve(request);
    }

    /**
     * Puts a reservation back, for a checkout that failed after reserving.
     *
     * <p>Best-effort compensation, not a rollback - see {@link StockService#release}. A caller that
     * never manages to call this leaves stock unavailable until an administrator notices.
     */
    @PostMapping("/releases")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@Valid @RequestBody ReservationRequest request) {
        stockService.release(request);
    }
}
