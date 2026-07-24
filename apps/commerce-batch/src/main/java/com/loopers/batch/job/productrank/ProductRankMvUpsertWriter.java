package com.loopers.batch.job.productrank;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

public class ProductRankMvUpsertWriter implements ItemWriter<ProductRankScoreDelta> {

    private final JdbcTemplate jdbcTemplate;
    private final LocalDate asOfDate;
    private final ProductRankMvTable table;

    public ProductRankMvUpsertWriter(JdbcTemplate jdbcTemplate, LocalDate asOfDate, ProductRankMvTable table) {
        this.jdbcTemplate = jdbcTemplate;
        this.asOfDate = asOfDate;
        this.table = table;
    }

    @Override
    public void write(Chunk<? extends ProductRankScoreDelta> chunk) {
        String sql = """
                INSERT INTO %s (as_of_date, product_id, score, view_sum, like_delta_sum, purchase_quantity_sum, created_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE
                    score = score + VALUES(score),
                    view_sum = view_sum + VALUES(view_sum),
                    like_delta_sum = like_delta_sum + VALUES(like_delta_sum),
                    purchase_quantity_sum = purchase_quantity_sum + VALUES(purchase_quantity_sum)
                """.formatted(table.tableName());

        List<Object[]> batchArgs = chunk.getItems().stream()
                .map(delta -> new Object[]{
                        asOfDate, delta.productId(), delta.scoreDelta(),
                        delta.viewDelta(), delta.likeDeltaDelta(), delta.purchaseDelta()
                })
                .toList();

        jdbcTemplate.batchUpdate(sql, batchArgs);
    }
}
