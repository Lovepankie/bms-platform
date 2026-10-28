package com.rincoltech.bms.core.help.internal;

/**
 * The setup helper's two chains (ADR-102), built from Ask BMS's providers and keys with the helper's own budget: the text
 * chain (Gemini, Groq, DeepSeek on Ask BMS's models) and the photo chain (Gemini, and Groq only with a vision model set).
 * Both pass {@link AskProviders}' checks, so neither can name Anthropic or Claude or call a host off the allow-list.
 */
record SetupProviders(AskProviders text, AskProviders photos) {}
