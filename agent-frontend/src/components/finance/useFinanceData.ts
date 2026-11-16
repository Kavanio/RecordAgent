import { useCallback, useEffect, useRef, useState } from 'react';
import {
  fetchFinanceCategories,
  fetchFinanceExpenseChart,
  fetchFinanceExpenseInsights,
  fetchFinanceOverview,
  fetchFinanceTransactions,
} from '../../services/financeApi';
import type {
  FinanceCategoryOptions,
  FinanceChartRange,
  FinanceExpenseChart,
  FinanceExpenseInsights,
  FinanceOverview,
  FinanceTransaction,
} from '../../types/finance';

/** 统一管理财务工作台概览、分类、趋势、分析与全量流水的异步读取与过期响应丢弃。 */
export function useFinanceData() {
  const [overview, setOverview] = useState<FinanceOverview | null>(null);
  const [categoryOptions, setCategoryOptions] = useState<FinanceCategoryOptions>({ expense: [], income: [] });
  const [chart, setChart] = useState<FinanceExpenseChart | null>(null);
  const [chartRange, setChartRange] = useState<FinanceChartRange>('month');
  const [insights, setInsights] = useState<FinanceExpenseInsights | null>(null);
  const [insightsRange, setInsightsRange] = useState<FinanceChartRange>('month');
  const [isInsightsLoading, setIsInsightsLoading] = useState(true);
  const [insightsError, setInsightsError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [overviewError, setOverviewError] = useState<string | null>(null);
  const [categoriesError, setCategoriesError] = useState<string | null>(null);
  const [isCategoriesLoading, setIsCategoriesLoading] = useState(true);
  const [isChartLoading, setIsChartLoading] = useState(true);
  const [chartError, setChartError] = useState<string | null>(null);
  const [allTransactions, setAllTransactions] = useState<FinanceTransaction[]>([]);
  const [isTransactionsLoading, setIsTransactionsLoading] = useState(false);
  const [transactionsError, setTransactionsError] = useState<string | null>(null);

  const chartRequestId = useRef(0);
  const insightsRequestId = useRef(0);
  const overviewRequestId = useRef(0);
  const categoriesRequestId = useRef(0);

  const load = useCallback(async () => {
    const requestId = ++overviewRequestId.current;
    setIsLoading(true);
    setOverviewError(null);
    try {
      const nextOverview = await fetchFinanceOverview();
      if (requestId === overviewRequestId.current) setOverview(nextOverview);
    } catch (cause) {
      if (requestId === overviewRequestId.current) {
        setOverviewError(cause instanceof Error ? cause.message : '财务数据加载失败，请稍后重试。');
      }
    } finally {
      if (requestId === overviewRequestId.current) setIsLoading(false);
    }
  }, []);

  // 分类失败只影响表单中的历史分类，不阻塞资产、流水和图表。
  const loadCategories = useCallback(async () => {
    const requestId = ++categoriesRequestId.current;
    setIsCategoriesLoading(true);
    setCategoriesError(null);
    try {
      const nextCategories = await fetchFinanceCategories();
      if (requestId === categoriesRequestId.current) setCategoryOptions(nextCategories);
    } catch (cause) {
      if (requestId === categoriesRequestId.current) {
        setCategoriesError(cause instanceof Error ? cause.message : '历史分类加载失败');
      }
    } finally {
      if (requestId === categoriesRequestId.current) setIsCategoriesLoading(false);
    }
  }, []);

  const loadChart = useCallback(async (range: FinanceChartRange) => {
    const requestId = ++chartRequestId.current;
    setIsChartLoading(true);
    setChartError(null);
    try {
      const nextChart = await fetchFinanceExpenseChart(range);
      if (requestId === chartRequestId.current) setChart(nextChart);
    } catch (cause) {
      if (requestId === chartRequestId.current) {
        setChartError(cause instanceof Error ? cause.message : '支出趋势加载失败，请稍后重试。');
      }
    } finally {
      if (requestId === chartRequestId.current) setIsChartLoading(false);
    }
  }, []);

  const loadInsights = useCallback(async (range: FinanceChartRange) => {
    const requestId = ++insightsRequestId.current;
    setIsInsightsLoading(true);
    setInsightsError(null);
    try {
      const nextInsights = await fetchFinanceExpenseInsights(range);
      if (requestId === insightsRequestId.current) setInsights(nextInsights);
    } catch (cause) {
      if (requestId === insightsRequestId.current) {
        setInsightsError(cause instanceof Error ? cause.message : '支出分析加载失败，请稍后重试。');
      }
    } finally {
      if (requestId === insightsRequestId.current) setIsInsightsLoading(false);
    }
  }, []);

  const loadAllTransactions = useCallback(async () => {
    setIsTransactionsLoading(true);
    setTransactionsError(null);
    try {
      setAllTransactions(await fetchFinanceTransactions());
    } catch (cause) {
      setTransactionsError(cause instanceof Error ? cause.message : '全部流水加载失败，请稍后重试。');
    } finally {
      setIsTransactionsLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => { void loadCategories(); }, [loadCategories]);
  useEffect(() => { void loadChart(chartRange); }, [chartRange, loadChart]);
  useEffect(() => { void loadInsights(insightsRange); }, [insightsRange, loadInsights]);
  useEffect(() => () => {
    overviewRequestId.current += 1;
    categoriesRequestId.current += 1;
    chartRequestId.current += 1;
    insightsRequestId.current += 1;
  }, []);

  return {
    overview,
    categoryOptions,
    chart,
    chartRange,
    setChartRange,
    insights,
    insightsRange,
    setInsightsRange,
    isInsightsLoading,
    insightsError,
    isLoading,
    overviewError,
    categoriesError,
    isCategoriesLoading,
    isChartLoading,
    chartError,
    allTransactions,
    isTransactionsLoading,
    transactionsError,
    load,
    loadCategories,
    loadChart,
    loadInsights,
    loadAllTransactions,
  };
}
