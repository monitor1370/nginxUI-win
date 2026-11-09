package com.nginxui.utils.metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 单条指标序列, 两级粒度环形缓冲(纯内存):
 * <ul>
 * <li>细粒度: 10s x 90 点 = 最近 15 分钟</li>
 * <li>粗粒度: 5min x 288 点 = 最近 24 小时, 每 {@link #COARSE_EVERY} 个细点由窗口均值生成</li>
 * </ul>
 * 线程安全: 全部读写 synchronized.
 */
public class MetricSeries {

	/** 细粒度保留点数(10s x 90 = 15min) */
	public static final int FINE_CAPACITY = 90;
	/** 粗粒度保留点数(5min x 288 = 24h) */
	public static final int COARSE_CAPACITY = 288;
	/** 每 30 个细点(30 x 10s = 5min)生成一个粗点 */
	public static final int COARSE_EVERY = 30;

	private final int dims;

	private final ArrayDeque<MetricPoint> fine = new ArrayDeque<MetricPoint>(FINE_CAPACITY + 1);
	private final ArrayDeque<MetricPoint> coarse = new ArrayDeque<MetricPoint>(COARSE_CAPACITY + 1);

	private long tickCount = 0L;

	public MetricSeries(int dims) {
		this.dims = dims;
	}

	public int getDims() {
		return dims;
	}

	/**
	 * 追加一个细粒度采样点, 并在到达 5 分钟边界时自动生成粗粒度均值点.
	 */
	public synchronized void appendFine(long t, double[] values) {
		double[] v = normalize(values);

		fine.addLast(new MetricPoint(t, v));
		while (fine.size() > FINE_CAPACITY) {
			fine.removeFirst();
		}

		tickCount++;
		if (tickCount % COARSE_EVERY == 0) {
			aggregateCoarse();
		}
	}

	/** 用最近 COARSE_EVERY 个细点(不足则用全部)的各维均值生成一个粗点 */
	private void aggregateCoarse() {
		int window = Math.min(COARSE_EVERY, fine.size());
		if (window == 0) {
			return;
		}

		double[] sum = new double[dims];
		long lastT = 0L;
		MetricPoint[] snapshot = fine.toArray(new MetricPoint[0]);
		for (int i = snapshot.length - window; i < snapshot.length; i++) {
			MetricPoint p = snapshot[i];
			double[] pv = p.getValues();
			for (int d = 0; d < dims; d++) {
				sum[d] += d < pv.length ? pv[d] : 0d;
			}
			lastT = p.getT();
		}

		double[] avg = new double[dims];
		for (int d = 0; d < dims; d++) {
			avg[d] = sum[d] / window;
		}
		coarse.addLast(new MetricPoint(lastT, avg));
		while (coarse.size() > COARSE_CAPACITY) {
			coarse.removeFirst();
		}
	}

	/**
	 * 取序列快照.
	 * @param range 24h 走粗粒度, 其余(默认 15m)走细粒度
	 */
	public synchronized List<MetricPoint> points(String range) {
		ArrayDeque<MetricPoint> source = "24h".equalsIgnoreCase(range) ? coarse : fine;
		List<MetricPoint> list = new ArrayList<MetricPoint>(source.size());
		for (MetricPoint p : source) {
			// 深拷贝, 避免调用方修改内部数组
			list.add(new MetricPoint(p.getT(), p.getValues()));
		}
		return list;
	}

	/** 最新细粒度点(用于"当前值"卡片), 无采样时返回 null */
	public synchronized MetricPoint latest() {
		MetricPoint p = fine.peekLast();
		return p == null ? null : new MetricPoint(p.getT(), p.getValues());
	}

	private double[] normalize(double[] values) {
		double[] v = values == null ? new double[dims] : Arrays.copyOf(values, values.length);
		if (v.length != dims) {
			double[] resized = new double[dims];
			System.arraycopy(v, 0, resized, 0, Math.min(v.length, dims));
			v = resized;
		}
		return v;
	}
}
