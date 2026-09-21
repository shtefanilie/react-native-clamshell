#import "AngleRuntimeBinding.h"

#include "AngleRuntimeBridge.hpp"

@implementation ClamshellAngleRuntimeBinding

+ (uint64_t)createChannel {
  return clamshell::createAngleChannel();
}

+ (void)activateChannel:(uint64_t)channel {
  clamshell::activateAngleChannel(channel);
}

+ (void)emitDegrees:(double)degrees channel:(uint64_t)channel {
  clamshell::emitAngleChannel(channel, degrees);
}

+ (void)releaseChannel:(uint64_t)channel {
  clamshell::releaseAngleChannel(channel);
}

@end
