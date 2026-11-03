package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.kernel.PersonalDataEraser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Removing a closed business (issue #571, ADR-090) deletes where its staff made each entry and where its shops are (issue
 * #826, ADR-122): a location says where a named person was, and no money record needs it.
 */
@Component
class EntryLocationErasure implements PersonalDataEraser {

    private final JdbcClient jdbc;

    EntryLocationErasure(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String area() {
        return "retail_locations";
    }

    @Override
    public int erase() {
        return jdbc.sql("DELETE FROM retail_entry_locations").update()
                + jdbc.sql("DELETE FROM retail_shop_locations").update();
    }
}
