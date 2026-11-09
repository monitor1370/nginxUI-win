package com.nginxui.utils;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * 轻量国际化入口: 基于 ResourceBundle, 默认跟随系统语言.
 *
 * 文案放在 resources/i18n/messages.properties(中文, 兜底) 与
 * messages_en.properties(英文). 未找到 key 时原样返回 key, 便于发现漏配.
 * 可通过 -Dnginxui.lang=en 强制指定语言.
 */
public final class I18n {

	private static final String BUNDLE_NAME = "i18n.messages";

	private static volatile ResourceBundle bundle;

	private I18n() {
	}

	private static ResourceBundle bundle() {
		ResourceBundle b = bundle;
		if (b == null) {
			synchronized (I18n.class) {
				b = bundle;
				if (b == null) {
					Locale locale = Locale.getDefault();
					String forced = System.getProperty("nginxui.lang");
					if (forced != null && !forced.trim().isEmpty()) {
						locale = Locale.forLanguageTag(forced.trim().replace('_', '-'));
					}
					b = ResourceBundle.getBundle(BUNDLE_NAME, locale);
					bundle = b;
				}
			}
		}
		return b;
	}

	/**
	 * 取文案并按 MessageFormat 填充占位符.
	 *
	 * @param key  文案 key
	 * @param args 占位符参数, 可为空
	 */
	public static String t(String key, Object... args) {
		if (key == null) {
			return "";
		}
		String pattern;
		try {
			pattern = bundle().getString(key);
		} catch (MissingResourceException e) {
			return key;
		}
		if (args == null || args.length == 0) {
			return pattern;
		}
		return MessageFormat.format(pattern, args);
	}
}
