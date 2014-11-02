package com.google.inject.weaver;

public interface WeavedInjector {
	public void injectFields(Object target, Object[] params);
}
