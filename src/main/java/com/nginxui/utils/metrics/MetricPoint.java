package com.nginxui.utils.metrics;

import java.util.Arrays;

/**
 * 单个时序采样点: epoch 毫秒时间戳 + 固定槽位的指标值.
 * 不可变, 数组经拷贝隔离.
 */
public class MetricPoint {

	private final long t;
	private final double[] values;

	public MetricPoint(long t, double[] values) {
		this.t = t;
		this.values = values == null ? new double[0] : Arrays.copyOf(values, values.length);
	}

	public long getT() {
		return t;
	}

	public double[] getValues() {
		return Arrays.copyOf(values, values.length);
	}

	public double value(int index) {
		return index >= 0 && index < values.length ? values[index] : 0d;
	}
}
