package com.mealtoken.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.mealtoken.audit.AuditService;
import com.mealtoken.entity.Hotel;
import com.mealtoken.entity.HotelQrCode;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.repository.HotelQrCodeRepository;
import com.mealtoken.repository.HotelRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Hotel QR codes. The QR holds only a URL with a random, non-guessable identifier: no personal data,
 * balances, or credentials. All checks happen on the server after the customer logs in.
 */
@Service
public class QrService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HotelQrCodeRepository qrs;
    private final HotelRepository hotels;
    private final AuditService audit;
    private final com.mealtoken.util.PublicUrl publicUrl;

    public QrService(HotelQrCodeRepository qrs, HotelRepository hotels, AuditService audit,
                     com.mealtoken.util.PublicUrl publicUrl) {
        this.qrs = qrs; this.hotels = hotels; this.audit = audit; this.publicUrl = publicUrl;
    }

    private static String newIdentifier() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Transactional
    public HotelQrCode ensureActive(Long hotelId) {
        return qrs.findByHotelIdAndStatus(hotelId, "ACTIVE").orElseGet(() -> create(hotelId));
    }

    @Transactional(readOnly = true)
    public Optional<HotelQrCode> active(Long hotelId) { return qrs.findByHotelIdAndStatus(hotelId, "ACTIVE"); }

    private HotelQrCode create(Long hotelId) {
        HotelQrCode q = new HotelQrCode();
        q.setHotelId(hotelId);
        q.setPublicIdentifier(newIdentifier());
        return qrs.saveAndFlush(q);
    }

    /** Revokes the current code (if any) and issues a new one. Old printouts stop working immediately. */
    @Transactional
    public HotelQrCode rotate(Long hotelId, Long actorId) {
        qrs.findByHotelIdAndStatus(hotelId, "ACTIVE").ifPresent(old -> {
            old.setStatus("REVOKED");
            old.setRevokedAt(Instant.now());
            qrs.saveAndFlush(old);
        });
        HotelQrCode q = create(hotelId);
        audit.log(actorId, hotelId, "QR_ROTATED", "HotelQrCode", q.getId(), null);
        return q;
    }

    @Transactional
    public void deactivate(Long hotelId, Long actorId) {
        qrs.findByHotelIdAndStatus(hotelId, "ACTIVE").ifPresent(q -> {
            q.setStatus("REVOKED");
            q.setRevokedAt(Instant.now());
            audit.log(actorId, hotelId, "QR_DEACTIVATED", "HotelQrCode", q.getId(), null);
        });
    }

    /** Resolves an identifier to its hotel; only ACTIVE codes of ACTIVE hotels resolve. */
    @Transactional(readOnly = true)
    public Hotel resolveHotel(String identifier) {
        if (identifier == null || identifier.length() > 64) throw new BusinessException("error.qr.invalid");
        HotelQrCode q = qrs.findByPublicIdentifierAndStatus(identifier, "ACTIVE")
                .orElseThrow(() -> new BusinessException("error.qr.invalid"));
        Hotel h = hotels.findById(q.getHotelId()).orElseThrow(() -> new BusinessException("error.qr.invalid"));
        if (!"ACTIVE".equals(h.getStatus())) throw new BusinessException("error.hotel.inactive");
        return h;
    }

    public String urlFor(HotelQrCode q) { return publicUrl.url("/customer/scan/" + q.getPublicIdentifier()); }

    public byte[] png(String content, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(m, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("QR generation failed", e);
        }
    }
}
