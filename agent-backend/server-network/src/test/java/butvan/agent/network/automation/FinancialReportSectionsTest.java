package butvan.agent.network.automation;

import butvan.agent.network.automation.service.FinancialReportSections;
import butvan.agent.network.daily.service.ExpenseAnalyticsService.ExpenseEntry;
import butvan.agent.network.finance.service.FinanceService;
import butvan.agent.network.finance.service.FinanceService.ReportAsset;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证稳定账户身份、币种边界、缺失来源与有界排行。 */
class FinancialReportSectionsTest {
    private final FinanceService finance = mock(FinanceService.class);
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");
    private final LocalDate current = LocalDate.of(2026, 9, 30), previous = LocalDate.of(2026, 8, 31);

    @Test void assetsMatchByIdAndSeparateNewMissingAndDifferentCurrencies() {
        when(finance.getReportAssets("owner", current, zone)).thenReturn(List.of(
                asset("a", "同名账户", "CNY", "120"), asset("b", "同名账户", "CNY", "50"),
                asset("c", "美元账户", "USD", "8")));
        when(finance.getReportAssets("owner", previous, zone)).thenReturn(List.of(
                asset("a", "旧名称", "CNY", "100"), asset("d", "已不在本期", "USD", "9")));
        var result = FinancialReportSections.assets(finance, "owner", current, previous, zone);
        var changed = result.metrics().stream().filter(m -> m.label().endsWith(" · a")).findFirst().orElseThrow();
        assertEquals("+20.00", changed.change());
        assertEquals("100.00", changed.previous());
        assertTrue(result.metrics().stream().anyMatch(m -> m.label().endsWith(" · b") && m.change().contains("新增账户")));
        assertTrue(result.metrics().stream().anyMatch(m -> m.label().endsWith(" · d") && m.change().contains("不等于亏损")));
        assertTrue(result.metrics().stream().anyMatch(m -> m.label().equals("资产合计 · CNY") && m.current().equals("170.00")));
        assertTrue(result.metrics().stream().anyMatch(m -> m.label().equals("资产合计 · USD") && m.current().equals("8.00")));
    }

    @Test void failedAssetSideNeverBecomesZeroOrNewAccount() {
        when(finance.getReportAssets("owner", current, zone)).thenThrow(new IllegalStateException("unavailable"));
        when(finance.getReportAssets("owner", previous, zone)).thenReturn(List.of(asset("a", "账户", "CNY", "100")));
        var result = FinancialReportSections.assets(finance, "owner", current, previous, zone);
        assertEquals("不可用", result.metrics().getFirst().current());
        assertEquals("无法比较", result.metrics().getFirst().change());
        assertTrue(result.detail().contains("本期资产暂不可用"));
    }

    @Test void rankingsAreBoundedWithinCurrencyAndIncludeRemainderAndCategoryShares() {
        var entries = List.of(expense("1", "CNY", "100"), expense("2", "CNY", "10"),
                expense("3", "CNY", "20"), expense("4", "CNY", "30"), expense("5", "USD", "1"));
        String detail = FinancialReportSections.expenseDetails(entries, 3);
        assertTrue(detail.indexOf("订单1") < detail.indexOf("订单4"));
        assertTrue(detail.indexOf("订单4") < detail.indexOf("订单3"));
        assertFalse(detail.contains("订单2"));
        assertTrue(detail.contains("其余 1 笔合计 10.00 CNY"));
        assertTrue(detail.contains("USD（共 1 笔）"));
        assertTrue(detail.contains("100%" ) || detail.contains("100.0%"));
        assertTrue(FinancialReportSections.expenseDetails(List.of(), 3).isEmpty());
    }

    private ReportAsset asset(String id, String name, String currency, String balance) {
        return new ReportAsset(id, name, currency, new BigDecimal(balance));
    }
    private ExpenseEntry expense(String id, String currency, String amount) {
        return new ExpenseEntry(id, current, LocalTime.NOON, "餐饮", "订单" + id,
                new BigDecimal(amount), currency, "finance", Instant.EPOCH);
    }
}
