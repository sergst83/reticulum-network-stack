package io.reticulum.channel;

import io.reticulum.channel.message.MessageBase;

import java.util.function.Function;

public interface MessageCallbackType extends Function<MessageBase, Boolean> {
}
