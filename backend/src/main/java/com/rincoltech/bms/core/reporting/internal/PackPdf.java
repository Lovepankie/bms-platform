package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.reporting.internal.Renderers.Header;
import com.rincoltech.bms.core.reporting.internal.Spool.Guard;
import com.rincoltech.bms.core.reporting.internal.Spool.TableMeta;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The combined PDF of a report pack (ADR-038 decisions 4 and 5, issue #229): a cover with the pack's parameters, the
 * contents and a sign off block, then every report of the pack in order. Memory is bounded as for one report: each report
 * is rendered to its own file through the shared {@code report} template, one at a time, and the files are joined on disk
 * by PDFBox with temporary-file storage, so no more than one report's page tree is in memory. Every report carries the
 * pack's number and says in its footer which part of the pack it is; the contents give the page of the whole file each
 * report starts on.
 */
final class PackPdf {

    static final String TEMPLATE = "report_pack";

    /** One report of the pack as it is printed. */
    record Section(Header header, Presentation presentation, List<TableMeta> tables, Spool spool) {}

    private PackPdf() {}

    static void write(
            Documents documents,
            ReportsProperties limits,
            Header h,
            Presentation p,
            String number,
            List<Section> sections,
            Path dir,
            Path target,
            Guard guard) {
        List<Path> files = new ArrayList<>();
        List<Integer> pages = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            Path file = dir.resolve("part-" + (i + 1) + ".pdf");
            String section = "part " + (i + 1) + " of " + sections.size();
            PdfReport.write(
                    documents,
                    limits,
                    s.header(),
                    s.presentation(),
                    number,
                    section,
                    s.tables(),
                    s.spool(),
                    file,
                    guard);
            files.add(file);
            pages.add(documents.pdfPages(file));
        }
        // The contents name the page each report starts on, which depends on the cover's own length: the cover is
        // laid out for one page, and again for its real length if it ran over (a long list of parameters).
        Path cover = dir.resolve("cover.pdf");
        int coverPages = 1;
        for (int attempt = 0; attempt < 3; attempt++) {
            int assumed = coverPages;
            PdfReport.renderWhenFree(
                    documents, TEMPLATE, cover, guard, () -> model(h, p, number, sections, pages, assumed));
            coverPages = documents.pdfPages(cover);
            if (coverPages == assumed) {
                break;
            }
        }
        guard.checkpoint();
        List<Path> all = new ArrayList<>();
        all.add(cover);
        all.addAll(files);
        documents.mergePdfs(all, target);
    }

    /** The cover's model: the letterhead, the parameters box, the contents and the sign off block. */
    static Map<String, Object> model(
            Header h, Presentation p, String number, List<Section> sections, List<Integer> pages, int coverPages) {
        Map<String, Object> lh = new LinkedHashMap<>();
        lh.put("name", p.businessName());
        lh.put("colour", p.brand());
        lh.put("onColour", p.onBrand());
        lh.put("tint", p.tint());
        lh.put("footer", p.footer());
        lh.put("logo", p.logo());

        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("name", p.scope());
        branch.put("location", null);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("title", h.title().toUpperCase(Locale.ENGLISH));
        doc.put("number", number);
        doc.put("date", ReportFormats.date(h.generatedAt().atZone(p.zone()).toLocalDate()));
        doc.put("section", "cover");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("asAt", ReportFormats.date(h.asOf()));
        report.put("currency", h.currency());
        report.put(
                "parameters",
                p.parameters().stream()
                        .map(l -> Map.<String, Object>of("label", l.label(), "value", l.value()))
                        .toList());
        report.put("madeLine", ReportFormats.dateTime(h.generatedAt(), p.zone()) + " (" + p.zone() + ") by " + p.by());
        report.put("readLine", ReportFormats.dateTime(h.snapshotAt(), p.zone()) + " (" + p.zone() + ")");
        report.put("notes", h.notes());

        List<Map<String, Object>> contents = new ArrayList<>();
        int start = coverPages + 1;
        for (int i = 0; i < sections.size(); i++) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("part", String.valueOf(i + 1));
            line.put("title", sections.get(i).header().title());
            line.put("asAt", ReportFormats.date(sections.get(i).header().asOf()));
            line.put("pages", ReportFormats.count(pages.get(i)));
            line.put("start", ReportFormats.count(start));
            contents.add(line);
            start += pages.get(i);
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("lh", lh);
        model.put("branch", branch);
        model.put("doc", doc);
        model.put("report", report);
        model.put("contents", contents);
        model.put("by", p.by());
        return model;
    }
}
