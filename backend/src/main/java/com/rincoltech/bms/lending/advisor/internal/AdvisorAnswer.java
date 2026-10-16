package com.rincoltech.bms.lending.advisor.internal;

import java.util.List;

/**
 * The brief as it is shown (ADR-037 decision 5 as amended in issue #223): a summary and one point per finding, every sentence
 * written by our renderer from the reviewed catalogue and the facts. The model never writes any of it; it answers in the
 * structured form of {@link StructuredValidator} and this is what our code makes of that.
 *
 * @param summary the opening sentence, from the template
 * @param points one per finding, one for the facts to watch; {@code facts} are the keys the text rests on
 */
record AdvisorAnswer(String summary, List<Point> points) {

    record Point(String text, List<String> facts) {}
}
