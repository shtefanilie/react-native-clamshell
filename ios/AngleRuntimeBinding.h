#pragma once

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface ClamshellAngleRuntimeBinding : NSObject

+ (uint64_t)createChannel;
+ (void)activateChannel:(uint64_t)channel;
+ (void)emitDegrees:(double)degrees channel:(uint64_t)channel;
+ (void)releaseChannel:(uint64_t)channel;

@end

NS_ASSUME_NONNULL_END
