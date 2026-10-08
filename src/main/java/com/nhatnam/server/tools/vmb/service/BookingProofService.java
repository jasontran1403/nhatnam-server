package com.nhatnam.server.tools.vmb.service;

import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.vmb.entity.*;
import com.nhatnam.server.tools.vmb.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * CRUD cho {@link BookingProof} — file bằng chứng của booking.
 *
 * ── Không đụng cờ sharedTicketFace ─────────────────────────
 * Mỗi lần upload, caller chỉ định scope qua {@code ticketId}:
 *   null      → gắn booking (chung).
 *   != null   → gắn ticket (riêng khách đó).
 *
 * Booking có thể có mixed: N file chung + M file riêng khách. Không cấm.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class BookingProofService {

    private final BookingRepository       bookingRepo;
    private final TicketRepository        ticketRepo;
    private final BookingProofRepository  proofRepo;
    private final VmbStorageService       storage;

    /**
     * Upload 1 file bằng chứng.
     * @param bookingId  bắt buộc
     * @param ticketId   nullable — nếu != null phải thuộc booking
     * @param file       ảnh hoặc PDF
     */
    @Transactional
    public BookingProof upload(Long bookingId, Long ticketId, MultipartFile file) {
        Booking b = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy booking."));
        Ticket t = null;
        if (ticketId != null) {
            t = ticketRepo.findById(ticketId)
                    .orElseThrow(() -> new ToolsException("Không tìm thấy vé."));
            if (!t.getBooking().getId().equals(bookingId)) {
                throw new ToolsException("Vé không thuộc booking này.");
            }
        }

        var stored = storage.store(file);
        BookingProof p = BookingProof.builder()
                .booking(b)
                .ticket(t)
                .storedName(stored.storedName())
                .originalName(stored.originalName())
                .contentType(stored.contentType())
                .sizeBytes(stored.size())
                .createdAt(System.currentTimeMillis())
                .build();
        return proofRepo.save(p);
    }

    @Transactional
    public void delete(Long proofId) {
        var p = proofRepo.findById(proofId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy file bằng chứng."));
        storage.delete(p.getStoredName());
        proofRepo.delete(p);
    }
}