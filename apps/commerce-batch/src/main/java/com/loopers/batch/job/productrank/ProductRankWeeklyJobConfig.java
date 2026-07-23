package com.loopers.batch.job.productrank;

import com.loopers.batch.listener.ChunkListener;
import com.loopers.batch.listener.JobListener;
import com.loopers.batch.listener.StepMonitorListener;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;

@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = ProductRankWeeklyJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Configuration
public class ProductRankWeeklyJobConfig {
    public static final String JOB_NAME = "productRankWeeklyJob";

    private static final String TABLE_NAME = "mv_product_rank_weekly";
    private static final int WINDOW_DAYS = 7;
    private static final int CHUNK_SIZE = 500;
    private static final String CLEANUP_STEP_NAME = "productRankWeeklyCleanupStep";
    private static final String AGGREGATE_STEP_NAME = "productRankWeeklyStep";

    private final JobRepository jobRepository;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final ProductRankScoreProcessor productRankScoreProcessor;
    private final JobListener jobListener;
    private final StepMonitorListener stepMonitorListener;
    private final ChunkListener chunkListener;

    @Bean(JOB_NAME)
    public Job productRankWeeklyJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(productRankWeeklyCleanupStep())
                .next(productRankWeeklyStep())
                .listener(jobListener)
                .build();
    }

    @JobScope
    @Bean(CLEANUP_STEP_NAME)
    public Step productRankWeeklyCleanupStep() {
        return new StepBuilder(CLEANUP_STEP_NAME, jobRepository)
                .tasklet(productRankMvCleanupTasklet(null), transactionManager)
                .listener(stepMonitorListener)
                .build();
    }

    @JobScope
    @Bean(AGGREGATE_STEP_NAME)
    public Step productRankWeeklyStep() {
        return new StepBuilder(AGGREGATE_STEP_NAME, jobRepository)
                .<ProductMetricDailyRow, ProductRankScoreDelta>chunk(CHUNK_SIZE, transactionManager)
                .reader(dailyMetricReader(null))
                .processor(productRankScoreProcessor)
                .writer(productRankMvUpsertWriter(null))
                .listener(stepMonitorListener)
                .listener(chunkListener)
                .build();
    }

    @StepScope
    @Bean
    public JdbcCursorItemReader<ProductMetricDailyRow> dailyMetricReader(
            @Value("#{jobParameters['requestDate']}") String requestDateText) {
        LocalDate requestDate = LocalDate.parse(requestDateText);
        LocalDate windowEnd = requestDate.minusDays(1);
        LocalDate windowStart = windowEnd.minusDays(WINDOW_DAYS - 1);

        return new JdbcCursorItemReaderBuilder<ProductMetricDailyRow>()
                .name("productRankWeeklyDailyMetricReader")
                .dataSource(dataSource)
                .sql("""
                        SELECT product_id, metric_date, view_count, like_delta_count, purchase_quantity
                        FROM product_metric_daily
                        WHERE metric_date BETWEEN ? AND ?
                        """)
                .preparedStatementSetter(ps -> {
                    ps.setDate(1, Date.valueOf(windowStart));
                    ps.setDate(2, Date.valueOf(windowEnd));
                })
                .rowMapper((rs, rowNum) -> new ProductMetricDailyRow(
                        rs.getString("product_id"),
                        rs.getDate("metric_date").toLocalDate(),
                        rs.getLong("view_count"),
                        rs.getLong("like_delta_count"),
                        rs.getLong("purchase_quantity")
                ))
                .build();
    }

    @StepScope
    @Bean
    public ProductRankMvUpsertWriter productRankMvUpsertWriter(
            @Value("#{jobParameters['requestDate']}") String requestDateText) {
        return new ProductRankMvUpsertWriter(jdbcTemplate, LocalDate.parse(requestDateText), TABLE_NAME);
    }

    @StepScope
    @Bean
    public ProductRankMvCleanupTasklet productRankMvCleanupTasklet(
            @Value("#{jobParameters['requestDate']}") String requestDateText) {
        return new ProductRankMvCleanupTasklet(jdbcTemplate, LocalDate.parse(requestDateText), TABLE_NAME);
    }
}
