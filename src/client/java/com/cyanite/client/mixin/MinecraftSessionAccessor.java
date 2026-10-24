package com.cyanite.client.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

/** Everything Minecraft ties to the logged-in account, so the account manager can swap it as a set. */
@Mixin(Minecraft.class)
public interface MinecraftSessionAccessor {
    @Mutable
    @Accessor("user")
    void cyanite$setUser(User user);

    @Mutable
    @Accessor("profileFuture")
    void cyanite$setProfileFuture(CompletableFuture<ProfileResult> future);

    @Mutable
    @Accessor("userApiService")
    void cyanite$setUserApiService(UserApiService service);

    @Mutable
    @Accessor("userPropertiesFuture")
    void cyanite$setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> future);

    @Mutable
    @Accessor("profileKeyPairManager")
    void cyanite$setProfileKeyPairManager(ProfileKeyPairManager manager);

    @Accessor("reportingContext")
    void cyanite$setReportingContext(ReportingContext context);
}
