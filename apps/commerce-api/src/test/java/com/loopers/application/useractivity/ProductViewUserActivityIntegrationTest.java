package com.loopers.application.useractivity;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.brand.BrandInfo;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.application.product.ProductInfo;
import com.loopers.domain.useractivity.UserActivityType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
class ProductViewUserActivityIntegrationTest {

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private BrandApplicationService brandApplicationService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("고객이 상품을 단건 조회하면 유저 활동 로그가 기록된다.")
    @Test
    void logsUserActivity_whenCustomerGetsProduct(CapturedOutput output) {
        // arrange
        BrandInfo brand = brandApplicationService.createBrand("나이키", "스포츠 브랜드");
        ProductInfo product = productApplicationService.createProduct(
                brand.id(), "에어맥스", "운동화 설명", 100_000L, 5);

        // act
        productApplicationService.getProductForCustomer(product.id());

        // assert
        assertThat(output)
                .contains("user_activity")
                .contains("type=" + UserActivityType.PRODUCT_VIEW)
                .contains("userId=ANONYMOUS")
                .contains("targetType=PRODUCT")
                .contains("targetId=" + product.id());
    }
}
