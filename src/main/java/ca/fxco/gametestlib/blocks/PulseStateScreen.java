package ca.fxco.gametestlib.blocks;

import ca.fxco.gametestlib.Utils.EventCheckbox;
import ca.fxco.gametestlib.gametest.block.BlockStateSuggestions;
import ca.fxco.gametestlib.network.GameTestNetwork;
import ca.fxco.gametestlib.network.packets.ServerboundSetPulseStatePacket;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

public class PulseStateScreen extends Screen {

    private final PulseStateBlockEntity blockEntity;
    private EditBox pulseDelayEdit;
    private EditBox pulseDurationEdit;
    private EditBox firstStateEdit;
    private EditBox pulseStateEdit;
    private EditBox lastStateEdit;
    private Checkbox disableFirstBlockUpdates;
    private BlockStateSuggestions blockStateSuggestionsFirst;
    private BlockStateSuggestions blockStateSuggestionsPulse;
    private BlockStateSuggestions blockStateSuggestionsLast;

    private boolean initialDisableFirstBlockUpdates;

    public PulseStateScreen(PulseStateBlockEntity blockEntity) {
        super(Component.translatable("screen.gametestlib.pulse_state_block.title"));
        this.blockEntity = blockEntity;
    }

    @Override
    public void tick() {
        this.pulseDelayEdit.tick();
        this.pulseDurationEdit.tick();
        this.firstStateEdit.tick();
        this.pulseStateEdit.tick();
        this.lastStateEdit.tick();
    }

    public void onCancel() {
        this.blockEntity.setDisableFirstBlockUpdates(initialDisableFirstBlockUpdates);
        this.minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        onCancel();
    }

    private void onDone() {
        HolderLookup<Block> holderLookup = BuiltInRegistries.BLOCK.asLookup();
        try {
            ServerboundSetPulseStatePacket packet = new ServerboundSetPulseStatePacket(
                    this.blockEntity.getBlockPos(),
                    Integer.parseInt(this.pulseDelayEdit.getValue()),
                    Integer.parseInt(this.pulseDurationEdit.getValue()),
                    BlockStateParser.parseForBlock(holderLookup, this.firstStateEdit.getValue(), true).blockState(),
                    BlockStateParser.parseForBlock(holderLookup, this.pulseStateEdit.getValue(), true).blockState(),
                    BlockStateParser.parseForBlock(holderLookup, this.lastStateEdit.getValue(), true).blockState()
            );
            GameTestNetwork.sendToServer(packet);
        } catch (CommandSyntaxException e) {
            e.printStackTrace();
        } // Just don't send if validation failed
        this.minecraft.setScreen(null);
    }

    @Override
    protected void init() {
        this.initialDisableFirstBlockUpdates = this.blockEntity.isDisableFirstBlockUpdates();

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, (button) -> {
            this.onDone();
        }).bounds(this.width / 2 - 4 - 150, 210, 150, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, (button) -> {
            this.onCancel();
        }).bounds(this.width / 2 + 4, 210, 150, 20).build());

        this.pulseDelayEdit = new EditBox(this.font, this.width / 2 - 150 - 4, 50, 150, 20, Component.translatable("screen.gametestlib.pulse_state_block.delay"));
        this.pulseDelayEdit.setMaxLength(6);
        this.pulseDelayEdit.setValue("" + this.blockEntity.getDelay());
        this.addWidget(this.pulseDelayEdit);
        this.pulseDurationEdit = new EditBox(this.font, this.width / 2 + 4, 50, 150, 20, Component.translatable("screen.gametestlib.pulse_state_block.duration"));
        this.pulseDurationEdit.setMaxLength(6);
        this.pulseDurationEdit.setValue("" + this.blockEntity.getDuration());
        this.addWidget(this.pulseDurationEdit);
        this.firstStateEdit = new EditBox(this.font, this.width / 2 - 154, 90, 280, 20, Component.translatable("screen.gametestlib.pulse_state_block.firstState"));
        this.firstStateEdit.setMaxLength(255);
        this.firstStateEdit.setValue(BlockStateParser.serialize(this.blockEntity.getFirstBlockState()));
        this.addWidget(this.firstStateEdit);
        this.pulseStateEdit = new EditBox(this.font, this.width / 2 - 154, 130, 308, 20, Component.translatable("screen.gametestlib.pulse_state_block.pulseState"));
        this.pulseStateEdit.setMaxLength(255);
        this.pulseStateEdit.setValue(BlockStateParser.serialize(this.blockEntity.getPulseBlockState()));
        this.addWidget(this.pulseStateEdit);
        this.lastStateEdit = new EditBox(this.font, this.width / 2 - 154, 170, 308, 20, Component.translatable("screen.gametestlib.pulse_state_block.lastState"));
        this.lastStateEdit.setMaxLength(255);
        this.lastStateEdit.setValue(BlockStateParser.serialize(this.blockEntity.getLastBlockState()));
        this.addWidget(this.lastStateEdit);

        this.disableFirstBlockUpdates = new EventCheckbox(this.width / 2 + 134, 90, 20, 20, Component.empty(), this.blockEntity.isDisableFirstBlockUpdates(), this.blockEntity::setDisableFirstBlockUpdates);
        this.addWidget(this.disableFirstBlockUpdates);

        this.setInitialFocus(this.pulseDelayEdit);

        this.blockStateSuggestionsFirst = new BlockStateSuggestions(this.minecraft, this, this.firstStateEdit, this.font, true, false, 0, 7, false, Integer.MIN_VALUE);
        this.blockStateSuggestionsFirst.setAllowSuggestions(true);
        this.blockStateSuggestionsFirst.updateCommandInfo();
        this.blockStateSuggestionsPulse = new BlockStateSuggestions(this.minecraft, this, this.pulseStateEdit, this.font, true, false, 0, 7, false, Integer.MIN_VALUE);
        this.blockStateSuggestionsPulse.setAllowSuggestions(true);
        this.blockStateSuggestionsPulse.updateCommandInfo();
        this.blockStateSuggestionsLast = new BlockStateSuggestions(this.minecraft, this, this.lastStateEdit, this.font, true, false, 0, 7, false, Integer.MIN_VALUE);
        this.blockStateSuggestionsLast.setAllowSuggestions(true);
        this.blockStateSuggestionsLast.updateCommandInfo();

        this.firstStateEdit.setResponder(str -> this.blockStateSuggestionsFirst.updateCommandInfo());
        this.pulseStateEdit.setResponder(str -> this.blockStateSuggestionsPulse.updateCommandInfo());
        this.lastStateEdit.setResponder(str -> this.blockStateSuggestionsLast.updateCommandInfo());
    }

    @Override
    public boolean keyPressed(int i, int j, int k) {
        if (this.blockStateSuggestionsFirst.keyPressed(i, j, k)) {
            return true;
        } else if (this.blockStateSuggestionsPulse.keyPressed(i, j, k)) {
            return true;
        } else if (this.blockStateSuggestionsLast.keyPressed(i, j, k)) {
            return true;
        } else if (super.keyPressed(i, j, k)) {
            return true;
        } else if (i != 257 && i != 335) {
            return false;
        }
        this.onDone();
        return true;
    }

    @Override
    public boolean mouseScrolled(double d, double e, double f) {
        return this.blockStateSuggestionsFirst.mouseScrolled(f) || this.blockStateSuggestionsPulse.mouseScrolled(f) ||
                this.blockStateSuggestionsLast.mouseScrolled(f) || super.mouseScrolled(d, e, f);
    }

    @Override
    public boolean mouseClicked(double d, double e, int i) {
        return this.blockStateSuggestionsFirst.mouseClicked(d, e, i) ||
                this.blockStateSuggestionsPulse.mouseClicked(d, e, i) ||
                this.blockStateSuggestionsLast.mouseClicked(d, e, i) ||
                super.mouseClicked(d, e, i);
    }

    @Override
    public void render(PoseStack poseStack, int i, int j, float f) {
        this.renderBackground(poseStack);
        drawCenteredString(poseStack, this.font, this.title, this.width / 2, 10, 16777215);

        drawString(poseStack, this.font, Component.translatable("screen.gametestlib.pulse_state_block.delay"), this.width / 2 - 153, 40, 10526880);
        this.pulseDelayEdit.render(poseStack, i, j, f);
        drawString(poseStack, this.font, Component.translatable("screen.gametestlib.pulse_state_block.duration"), this.width / 2 + 4, 40, 10526880);
        this.pulseDurationEdit.render(poseStack, i, j, f);
        drawString(poseStack, this.font, Component.translatable("screen.gametestlib.pulse_state_block.firstState"), this.width / 2 - 153, 80, 10526880);
        this.firstStateEdit.render(poseStack, i, j, f);
        drawString(poseStack, this.font, Component.translatable("screen.gametestlib.pulse_state_block.pulseState"), this.width / 2 - 153, 120, 10526880);
        this.pulseStateEdit.render(poseStack, i, j, f);
        this.lastStateEdit.render(poseStack, i, j, f);
        Component comp = Component.translatable("screen.gametestlib.pulse_state_block.disableUpdate");
        drawString(poseStack, this.font, comp, (this.width / 2 + 153) - this.font.width(comp), 80, 10526880);
        this.disableFirstBlockUpdates.render(poseStack, i, j, f);

        super.render(poseStack, i, j, f);
        this.blockStateSuggestionsFirst.render(poseStack, i, j);
        this.blockStateSuggestionsPulse.render(poseStack, i, j);
        this.blockStateSuggestionsLast.render(poseStack, i, j);
    }
}
