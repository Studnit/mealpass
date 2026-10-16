package com.mealtoken.controller;

import com.mealtoken.exception.BusinessException;
import com.mealtoken.security.AccessService;
import com.mealtoken.security.AppUserDetails;
import com.mealtoken.security.CurrentUser;
import com.mealtoken.security.HotelContext;
import com.mealtoken.service.ReportService;
import com.mealtoken.service.ReportService.Def;
import com.mealtoken.service.ReportService.Query;
import com.mealtoken.service.ReportService.Result;
import com.mealtoken.service.ReportService.Scope;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

/** Paged, searchable, sortable, exportable tables for owners, staff and customers (one engine, scoped by session). */
@Controller
public class TableController {
    private static final Map<String, String> SECTION = Map.ofEntries(
            Map.entry("attendance", "attendance"), Map.entry("ledger", "ledger"), Map.entry("payments", "payments"),
            Map.entry("subscriptions", "customers"), Map.entry("audit", "audit"),
            Map.entry("my_attendance", "c_attendance"), Map.entry("my_tokens", "c_tokens"), Map.entry("my_payments", "c_payments"));

    private final ReportService reports;
    private final AccessService access;

    public TableController(ReportService reports, AccessService access) { this.reports = reports; this.access = access; }

    // ------------------------------------------------------------------ owner / staff

    @GetMapping("/{area:owner|staff}/table/{type}")
    public String hotelTable(@PathVariable String area, @PathVariable String type, Params p, Model m) {
        HotelContext ctx = access.current();
        Result r = hotelResult(ctx, area, type, p, true);
        m.addAttribute("result", r);
        m.addAttribute("def", r.getDef());
        m.addAttribute("type", type);
        m.addAttribute("area", area);
        m.addAttribute("base", "/" + area + "/table/" + type);
        m.addAttribute("isOwner", ctx.owner());
        m.addAttribute("canConfirm", ctx.owner() || ctx.canConfirmPayments());
        m.addAttribute("tz", ctx.hotel().getTimezone());
        m.addAttribute("section", "ACTIVE".equals(r.getQuery().status()) && "subscriptions".equals(type) ? "active"
                : SECTION.getOrDefault(type, "reports"));
        return "shared/table";
    }

    @GetMapping("/{area:owner|staff}/table/{type}/export")
    public ResponseEntity<byte[]> hotelExport(@PathVariable String area, @PathVariable String type, Params p) {
        HotelContext ctx = access.current();
        Result r = hotelResult(ctx, area, type, p, false);
        return csv(reports.csv(r), type);
    }

    private Result hotelResult(HotelContext ctx, String area, String type, Params p, boolean paged) {
        Def d = ReportService.def(type).filter(x -> x.scope() == Scope.HOTEL).orElseThrow(() -> new BusinessException("error.notfound"));
        if (!ctx.owner()) { // staff: attendance always; payments only when the owner granted that permission
            boolean allowed = type.equals("attendance") || (type.equals("payments") && ctx.canConfirmPayments());
            if (!allowed || !area.equals("staff")) throw new BusinessException("error.forbidden");
        }
        return reports.run(d, ctx.hotelId(), ctx.hotel().getTimezone(), p.toQuery(), paged);
    }

    // ------------------------------------------------------------------ customer

    @GetMapping("/customer/table/{type}")
    public String customerTable(@PathVariable String type, Params p, Model m) {
        AppUserDetails u = CurrentUser.require();
        Def d = ReportService.def(type).filter(x -> x.scope() == Scope.CUSTOMER).orElseThrow(() -> new BusinessException("error.notfound"));
        Result r = reports.run(d, u.getId(), "Asia/Kolkata", p.toQuery(), true);
        m.addAttribute("result", r);
        m.addAttribute("def", d);
        m.addAttribute("type", type);
        m.addAttribute("area", "customer");
        m.addAttribute("base", "/customer/table/" + type);
        m.addAttribute("isOwner", false);
        m.addAttribute("canConfirm", false);
        m.addAttribute("section", SECTION.getOrDefault(type, "dashboard"));
        return "shared/table";
    }

    @GetMapping("/customer/table/{type}/export")
    public ResponseEntity<byte[]> customerExport(@PathVariable String type, Params p) {
        AppUserDetails u = CurrentUser.require();
        Def d = ReportService.def(type).filter(x -> x.scope() == Scope.CUSTOMER).orElseThrow(() -> new BusinessException("error.notfound"));
        return csv(reports.csv(reports.run(d, u.getId(), "Asia/Kolkata", p.toQuery(), false)), type);
    }

    private static ResponseEntity<byte[]> csv(String body, String type) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header("Content-Disposition", "attachment; filename=\"" + type + "-" + LocalDate.now() + ".csv\"")
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    /** Bound from the query string; every field is optional. */
    public static class Params {
        private String q, status, sort, dir;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate from, to;
        private int page;

        public void setQ(String v) { q = v; }
        public void setStatus(String v) { status = v; }
        public void setSort(String v) { sort = v; }
        public void setDir(String v) { dir = v; }
        public void setFrom(LocalDate v) { from = v; }
        public void setTo(LocalDate v) { to = v; }
        public void setPage(int v) { page = v; }

        Query toQuery() {
            return new Query(q, status, from, to, sort, !"asc".equalsIgnoreCase(dir), page);
        }
    }
}
