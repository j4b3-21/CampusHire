package com.campushire.placement.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.shared.web.ETags;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.placement.internal.OfferService;
import com.campushire.placement.internal.OfferService.NewOffer;
import com.campushire.placement.internal.OfferService.OfferView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
class OfferController {

    private static final String OFFERS = "/api/v1/orgs/{orgId}/campushire/offers";

    private final OfferService offers;

    OfferController(OfferService offers) {
        this.offers = offers;
    }

    @PostMapping(OFFERS)
    ResponseEntity<OfferView> create(CurrentUser user, @PathVariable UUID orgId,
            @Valid @RequestBody CreateOfferRequest request) {
        OfferView offer = offers.create(user, orgId, new NewOffer(request.applicationId(), request.ctc(),
                request.offerType(), request.joiningDate(), request.respondBy(), request.offerLetterFileId(),
                Boolean.TRUE.equals(request.override())));
        return ResponseEntity.created(URI.create("/api/v1/orgs/" + orgId + "/modules/offer/records/" + offer.id()))
                .eTag(ETags.of(offer.version()))
                .body(offer);
    }

    @PostMapping(OFFERS + "/{offerId}/accept")
    ResponseEntity<OfferView> accept(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID offerId) {
        OfferView offer = offers.accept(user, orgId, offerId);
        return ResponseEntity.ok().eTag(ETags.of(offer.version())).body(offer);
    }

    @PostMapping(OFFERS + "/{offerId}/decline")
    ResponseEntity<OfferView> decline(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID offerId) {
        OfferView offer = offers.decline(user, orgId, offerId);
        return ResponseEntity.ok().eTag(ETags.of(offer.version())).body(offer);
    }

    record CreateOfferRequest(@NotNull UUID applicationId, @NotNull BigDecimal ctc, @Size(max = 32) String offerType,
            LocalDate joiningDate, LocalDate respondBy, @Size(max = 200) String offerLetterFileId, Boolean override) {
    }
}
